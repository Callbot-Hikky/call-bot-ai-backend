package com.callbot.ai.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.MenuFileOrderRequest;
import com.callbot.ai.dto.MenuFileResponse;
import com.callbot.ai.dto.MenuFileSummary;
import com.callbot.ai.dto.MenuLimits;
import com.callbot.ai.dto.MenuRequest;
import com.callbot.ai.dto.MenuResponse;
import com.callbot.ai.dto.PublicMenuResponse;
import com.callbot.ai.exception.MenuFileException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.RestaurantMenu;
import com.callbot.ai.model.RestaurantMenuFile;
import com.callbot.ai.repository.RestaurantMenuFileRepository;
import com.callbot.ai.repository.RestaurantMenuRepository;
import com.callbot.ai.repository.RestaurantRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Menu d'un restaurant : mode publie (aucun, PDF, images, saisie manuelle),
 * contenu saisi a la main (document JSON du front stocke tel quel, comme le plan
 * de salle) et fichiers en base. Le type d'un fichier est detecte sur ses octets.
 *
 * <p>Invariant : le mode publie a toujours un contenu (un PDF, au moins une
 * image, ou une saisie non vide). Il est garanti a l'enregistrement et a la
 * suppression d'un fichier. Les listages ne chargent jamais les octets.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MenuService {

    private static final MenuLimits LIMITS = MenuLimits.DEFAULT;
    // Le front plafonne a 20 sections de 50 plats (nom 80, description 200) : 512 Ko couvre
    // tres largement une carte legitime, et ferme la porte a un document arbitraire.
    static final int MANUAL_MAX_CHARS = 512 * 1024;

    private final RestaurantMenuRepository menuRepository;
    private final RestaurantMenuFileRepository fileRepository;
    private final RestaurantRepository restaurantRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public MenuResponse get(UUID restaurantId) {
        requireRestaurant(restaurantId);
        return toResponse(restaurantId, menuRepository.findById(restaurantId).orElse(null));
    }

    /**
     * Cree le menu au premier enregistrement, remplace le mode ensuite. Un
     * {@code manual} absent conserve le contenu deja saisi : changer de mode ne
     * doit jamais effacer la carte tapee a la main.
     */
    public MenuResponse upsert(UUID restaurantId, MenuRequest request) {
        requireRestaurant(restaurantId);
        JsonNode manual = request.manual();
        if (manual != null && !manual.isObject()) {
            throw new MenuFileException(HttpStatus.BAD_REQUEST, "invalid_manual",
                    "The manual menu must be a JSON object");
        }
        RestaurantMenu menu = menuRepository.findById(restaurantId)
                .orElseGet(() -> RestaurantMenu.builder().restaurantId(restaurantId).build());
        String content = manual != null ? manual.toString()
                : (menu.getManualContent() == null ? "{}" : menu.getManualContent());
        if (content.length() > MANUAL_MAX_CHARS) {
            throw new MenuFileException(HttpStatus.PAYLOAD_TOO_LARGE, "manual_too_large",
                    "The manual menu exceeds " + MANUAL_MAX_CHARS + " characters");
        }
        requireModeReady(restaurantId, request.mode(), content);
        menu.setMode(request.mode());
        menu.setManualContent(content);
        return toResponse(restaurantId, menuRepository.save(menu));
    }

    /**
     * Ajoute un fichier. Le type est lu sur les octets, jamais sur le nom ni sur
     * le Content-Type annonce. Un PDF remplace le PDF precedent ; une image
     * s'ajoute apres la derniere position, huit au maximum.
     */
    public MenuResponse upload(UUID restaurantId, byte[] bytes) {
        requireRestaurant(restaurantId);
        fileRepository.lockMenu(restaurantId);
        MenuFileType type = MenuFileType.detect(bytes)
                .orElseThrow(() -> new MenuFileException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "unsupported_file_type", "Only PDF, JPEG, PNG and WebP files are accepted"));

        long max = type == MenuFileType.PDF ? LIMITS.pdfMaxBytes() : LIMITS.imageMaxBytes();
        if (bytes.length > max) {
            throw new MenuFileException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                    "File exceeds the maximum size of " + max + " bytes");
        }

        int position = 0;
        if (type == MenuFileType.PDF) {
            fileRepository.deleteByRestaurantIdAndKind(restaurantId, RestaurantMenuFile.KIND_PDF);
        } else {
            List<MenuFileSummary> images = images(restaurantId);
            if (images.size() >= LIMITS.imageMaxCount()) {
                throw new MenuFileException(HttpStatus.CONFLICT, "too_many_files",
                        "A menu holds at most " + LIMITS.imageMaxCount() + " images");
            }
            position = images.isEmpty() ? 0 : images.get(images.size() - 1).getPosition() + 1;
        }

        fileRepository.save(RestaurantMenuFile.builder()
                .restaurantId(restaurantId)
                .kind(type.kind())
                .position(position)
                .contentType(type.contentType())
                .sizeBytes(bytes.length)
                .data(bytes)
                .build());
        return toResponse(restaurantId, menuRepository.findById(restaurantId).orElse(null));
    }

    /**
     * Supprime un fichier. Les images restantes sont renumerotees sans trou, et
     * si le mode publie n'a plus aucun fichier, le menu repasse en « aucun ».
     */
    public MenuResponse deleteFile(UUID restaurantId, UUID fileId) {
        requireRestaurant(restaurantId);
        fileRepository.lockMenu(restaurantId);
        MenuFileSummary file = fileRepository.findSummaryByIdAndRestaurantId(fileId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("MenuFile", fileId));
        fileRepository.deleteByIdAndRestaurantId(fileId, restaurantId);
        if (RestaurantMenuFile.KIND_IMAGE.equals(file.getKind())) {
            renumber(restaurantId, images(restaurantId));
        }
        RestaurantMenu menu = menuRepository.findById(restaurantId).orElse(null);
        if (menu != null && file.getKind().equals(kindForMode(menu.getMode()))
                && fileRepository.countByRestaurantIdAndKind(restaurantId, file.getKind()) == 0) {
            menu.setMode(RestaurantMenu.MODE_NONE);
            menu = menuRepository.save(menu);
        }
        return toResponse(restaurantId, menu);
    }

    /**
     * Reordonne les images : position = index de l'id dans la liste recue. La
     * liste doit contenir chaque image du menu exactement une fois, sinon deux
     * images finiraient a la meme position. Le PDF n'a pas d'ordre.
     */
    public MenuResponse reorder(UUID restaurantId, MenuFileOrderRequest request) {
        requireRestaurant(restaurantId);
        fileRepository.lockMenu(restaurantId);
        List<MenuFileSummary> images = images(restaurantId);
        List<UUID> ids = request.fileIds();
        Set<UUID> expected = images.stream().map(MenuFileSummary::getId).collect(Collectors.toSet());
        boolean sameSet = ids.size() == expected.size()
                && new HashSet<>(ids).size() == ids.size()
                && expected.containsAll(ids);
        if (!sameSet) {
            throw new MenuFileException(HttpStatus.BAD_REQUEST, "invalid_file_order",
                    "The order must list every image of the menu exactly once");
        }
        for (int i = 0; i < ids.size(); i++) {
            fileRepository.updatePosition(ids.get(i), restaurantId, i);
        }
        return toResponse(restaurantId, menuRepository.findById(restaurantId).orElse(null));
    }

    /** Vue publique : nom du restaurant, mode, contenu, et seulement les fichiers du mode courant. */
    @Transactional(readOnly = true)
    public PublicMenuResponse getPublic(UUID restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantId));
        RestaurantMenu menu = menuRepository.findById(restaurantId).orElse(null);
        String mode = menu == null ? RestaurantMenu.MODE_NONE : menu.getMode();
        String kindForMode = kindForMode(mode);
        List<MenuFileResponse> files = new ArrayList<>();
        if (kindForMode != null) {
            for (MenuFileSummary file : fileRepository.findSummariesByRestaurantIdOrderByPositionAscCreatedAtAsc(restaurantId)) {
                if (kindForMode.equals(file.getKind())) {
                    files.add(toFileResponse(file, true));
                }
            }
        }
        JsonNode manual = RestaurantMenu.MODE_MANUAL.equals(mode) && menu != null
                ? parse(menu.getManualContent())
                : null;
        return new PublicMenuResponse(restaurant.getName(), mode, manual, files);
    }

    /** Fichier servi au public : seulement s'il appartient au restaurant ET au mode publie. */
    @Transactional(readOnly = true)
    public RestaurantMenuFile getPublicFile(UUID restaurantId, UUID fileId) {
        RestaurantMenuFile file = getFile(restaurantId, fileId);
        String publishedKind = menuRepository.findById(restaurantId)
                .map(menu -> kindForMode(menu.getMode()))
                .orElse(null);
        if (!file.getKind().equals(publishedKind)) {
            throw new ResourceNotFoundException("MenuFile", fileId);
        }
        return file;
    }

    /** Fichier pour l'apercu du restaurateur, quel que soit le mode publie. */
    @Transactional(readOnly = true)
    public RestaurantMenuFile getFile(UUID restaurantId, UUID fileId) {
        return fileRepository.findByIdAndRestaurantId(fileId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("MenuFile", fileId));
    }

    private List<MenuFileSummary> images(UUID restaurantId) {
        return fileRepository.findSummariesByRestaurantIdOrderByPositionAscCreatedAtAsc(restaurantId).stream()
                .filter(f -> RestaurantMenuFile.KIND_IMAGE.equals(f.getKind()))
                .toList();
    }

    private void renumber(UUID restaurantId, List<MenuFileSummary> images) {
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getPosition() != i) {
                fileRepository.updatePosition(images.get(i).getId(), restaurantId, i);
            }
        }
    }

    /** Un mode ne peut etre publie que si son contenu existe (regles de la spec, section 4). */
    private void requireModeReady(UUID restaurantId, String mode, String manualContent) {
        boolean ready = switch (mode) {
            case RestaurantMenu.MODE_PDF ->
                fileRepository.countByRestaurantIdAndKind(restaurantId, RestaurantMenuFile.KIND_PDF) >= 1;
            case RestaurantMenu.MODE_IMAGES ->
                fileRepository.countByRestaurantIdAndKind(restaurantId, RestaurantMenuFile.KIND_IMAGE) >= 1;
            case RestaurantMenu.MODE_MANUAL -> !parse(manualContent).isEmpty();
            default -> true;
        };
        if (!ready) {
            throw new MenuFileException(HttpStatus.CONFLICT, "mode_not_ready",
                    "Mode '" + mode + "' cannot be published: its content is missing");
        }
    }

    private static String kindForMode(String mode) {
        return switch (mode) {
            case RestaurantMenu.MODE_PDF -> RestaurantMenuFile.KIND_PDF;
            case RestaurantMenu.MODE_IMAGES -> RestaurantMenuFile.KIND_IMAGE;
            default -> null;
        };
    }

    private MenuResponse toResponse(UUID restaurantId, RestaurantMenu menu) {
        List<MenuFileResponse> files = fileRepository.findSummariesByRestaurantIdOrderByPositionAscCreatedAtAsc(restaurantId).stream()
                .map(f -> toFileResponse(f, false))
                .toList();
        return new MenuResponse(
                restaurantId,
                menu == null ? RestaurantMenu.MODE_NONE : menu.getMode(),
                parse(menu == null || menu.getManualContent() == null ? "{}" : menu.getManualContent()),
                files,
                LIMITS);
    }

    /** L'URL admin sert l'apercu du restaurateur ; l'URL publique ne sert que le mode publie. */
    private MenuFileResponse toFileResponse(MenuFileSummary file, boolean publicUrl) {
        String base = publicUrl ? "/api/public/restaurants/" : "/api/restaurants/";
        String url = base + file.getRestaurantId() + "/menu/files/" + file.getId();
        return new MenuFileResponse(file.getId(), file.getKind(), file.getContentType(),
                file.getPosition(), file.getSizeBytes(), url);
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            // Injoignable en pratique : la colonne est JSONB, Postgres valide le contenu.
            throw new IllegalStateException("Stored menu content is not valid JSON", e);
        }
    }

    private void requireRestaurant(UUID restaurantId) {
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant", restaurantId);
        }
    }
}
