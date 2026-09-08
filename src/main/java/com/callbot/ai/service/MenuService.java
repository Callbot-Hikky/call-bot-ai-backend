package com.callbot.ai.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.MenuFileOrderRequest;
import com.callbot.ai.dto.MenuFileResponse;
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
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MenuService {

    private static final MenuLimits LIMITS = MenuLimits.DEFAULT;

    private final RestaurantMenuRepository menuRepository;
    private final RestaurantMenuFileRepository fileRepository;
    private final RestaurantRepository restaurantRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public MenuResponse get(UUID restaurantId) {
        requireRestaurant(restaurantId);
        return toResponse(restaurantId, menuRepository.findById(restaurantId).orElse(null));
    }

    /** Cree le menu au premier enregistrement, remplace mode et contenu ensuite. */
    public MenuResponse upsert(UUID restaurantId, MenuRequest request) {
        requireRestaurant(restaurantId);
        JsonNode manual = request.manual();
        if (manual != null && !manual.isObject()) {
            throw new MenuFileException(HttpStatus.BAD_REQUEST, "invalid_manual",
                    "The manual menu must be a JSON object");
        }
        requireModeReady(restaurantId, request.mode(), manual);
        RestaurantMenu menu = menuRepository.findById(restaurantId)
                .orElseGet(() -> RestaurantMenu.builder().restaurantId(restaurantId).build());
        menu.setMode(request.mode());
        menu.setManualContent(request.manual() == null ? "{}" : request.manual().toString());
        return toResponse(restaurantId, menuRepository.save(menu));
    }

    /**
     * Ajoute un fichier. Le type est lu sur les octets, jamais sur le nom ni sur
     * le Content-Type annonce. Un PDF remplace le PDF precedent ; une image
     * s'ajoute en fin d'ordre, huit au maximum.
     */
    public MenuResponse upload(UUID restaurantId, byte[] bytes) {
        requireRestaurant(restaurantId);
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
            long count = fileRepository.countByRestaurantIdAndKind(restaurantId, RestaurantMenuFile.KIND_IMAGE);
            if (count >= LIMITS.imageMaxCount()) {
                throw new MenuFileException(HttpStatus.CONFLICT, "too_many_files",
                        "A menu holds at most " + LIMITS.imageMaxCount() + " images");
            }
            position = (int) count;
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

    public MenuResponse deleteFile(UUID restaurantId, UUID fileId) {
        requireRestaurant(restaurantId);
        RestaurantMenuFile file = fileRepository.findByIdAndRestaurantId(fileId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("MenuFile", fileId));
        fileRepository.delete(file);
        return toResponse(restaurantId, menuRepository.findById(restaurantId).orElse(null));
    }

    /**
     * Reordonne les images : position = index de l'id dans la liste recue. La
     * liste doit contenir chaque image du menu exactement une fois, sinon deux
     * images finiraient a la meme position. Le PDF n'a pas d'ordre.
     */
    public MenuResponse reorder(UUID restaurantId, MenuFileOrderRequest request) {
        requireRestaurant(restaurantId);
        List<RestaurantMenuFile> images = fileRepository.findByRestaurantIdOrderByPositionAsc(restaurantId).stream()
                .filter(f -> RestaurantMenuFile.KIND_IMAGE.equals(f.getKind()))
                .toList();
        List<UUID> ids = request.fileIds();
        Set<UUID> expected = images.stream().map(RestaurantMenuFile::getId).collect(Collectors.toSet());
        boolean sameSet = ids.size() == expected.size()
                && new HashSet<>(ids).size() == ids.size()
                && expected.containsAll(ids);
        if (!sameSet) {
            throw new MenuFileException(HttpStatus.BAD_REQUEST, "invalid_file_order",
                    "The order must list every image of the menu exactly once");
        }
        Map<UUID, RestaurantMenuFile> byId = images.stream()
                .collect(Collectors.toMap(RestaurantMenuFile::getId, Function.identity()));
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setPosition(i);
        }
        fileRepository.saveAll(images);
        return toResponse(restaurantId, menuRepository.findById(restaurantId).orElse(null));
    }

    /** Vue publique : nom du restaurant, mode, contenu, et seulement les fichiers du mode courant. */
    @Transactional(readOnly = true)
    public PublicMenuResponse getPublic(UUID restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantId));
        RestaurantMenu menu = menuRepository.findById(restaurantId).orElse(null);
        String mode = menu == null ? RestaurantMenu.MODE_NONE : menu.getMode();
        String kindForMode = switch (mode) {
            case RestaurantMenu.MODE_PDF -> RestaurantMenuFile.KIND_PDF;
            case RestaurantMenu.MODE_IMAGES -> RestaurantMenuFile.KIND_IMAGE;
            default -> null;
        };
        List<MenuFileResponse> files = new ArrayList<>();
        if (kindForMode != null) {
            for (RestaurantMenuFile file : fileRepository.findByRestaurantIdOrderByPositionAsc(restaurantId)) {
                if (kindForMode.equals(file.getKind())) {
                    files.add(toFileResponse(file));
                }
            }
        }
        JsonNode manual = RestaurantMenu.MODE_MANUAL.equals(mode) && menu != null
                ? parse(menu.getManualContent())
                : null;
        return new PublicMenuResponse(restaurant.getName(), mode, manual, files);
    }

    @Transactional(readOnly = true)
    public RestaurantMenuFile getFile(UUID restaurantId, UUID fileId) {
        return fileRepository.findByIdAndRestaurantId(fileId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("MenuFile", fileId));
    }

    private MenuResponse toResponse(UUID restaurantId, RestaurantMenu menu) {
        List<MenuFileResponse> files = fileRepository.findByRestaurantIdOrderByPositionAsc(restaurantId).stream()
                .map(this::toFileResponse)
                .toList();
        return new MenuResponse(
                restaurantId,
                menu == null ? RestaurantMenu.MODE_NONE : menu.getMode(),
                parse(menu == null ? "{}" : menu.getManualContent()),
                files,
                LIMITS);
    }

    private MenuFileResponse toFileResponse(RestaurantMenuFile file) {
        String url = "/api/public/restaurants/" + file.getRestaurantId() + "/menu/files/" + file.getId();
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

    /** Un mode ne peut etre publie que si son contenu existe (regles de la spec, section 4). */
    private void requireModeReady(UUID restaurantId, String mode, JsonNode manual) {
        boolean ready = switch (mode) {
            case RestaurantMenu.MODE_PDF ->
                fileRepository.countByRestaurantIdAndKind(restaurantId, RestaurantMenuFile.KIND_PDF) >= 1;
            case RestaurantMenu.MODE_IMAGES ->
                fileRepository.countByRestaurantIdAndKind(restaurantId, RestaurantMenuFile.KIND_IMAGE) >= 1;
            case RestaurantMenu.MODE_MANUAL -> manual != null && !manual.isEmpty();
            default -> true;
        };
        if (!ready) {
            throw new MenuFileException(HttpStatus.CONFLICT, "mode_not_ready",
                    "Mode '" + mode + "' cannot be published: its content is missing");
        }
    }

    private void requireRestaurant(UUID restaurantId) {
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant", restaurantId);
        }
    }
}
