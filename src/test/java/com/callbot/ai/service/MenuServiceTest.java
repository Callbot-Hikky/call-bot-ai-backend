package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.callbot.ai.dto.MenuFileOrderRequest;
import com.callbot.ai.dto.MenuFileSummary;
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
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock
    private RestaurantMenuRepository menuRepository;
    @Mock
    private RestaurantMenuFileRepository fileRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks
    private MenuService menuService;

    private final UUID restaurantId = UUID.randomUUID();

    private static byte[] pdf(int size) {
        byte[] bytes = new byte[size];
        byte[] magic = "%PDF-1.7 ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }

    private static byte[] png() {
        byte[] bytes = new byte[64];
        byte[] magic = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }

    private MenuFileSummary summary(UUID id, String kind, int position) {
        UUID rid = restaurantId;
        return new MenuFileSummary() {
            public UUID getId() { return id; }
            public UUID getRestaurantId() { return rid; }
            public String getKind() { return kind; }
            public int getPosition() { return position; }
            public String getContentType() { return kind.equals("pdf") ? "application/pdf" : "image/png"; }
            public long getSizeBytes() { return 1; }
        };
    }

    private RestaurantMenuFile entity(UUID id, String kind) {
        return RestaurantMenuFile.builder().id(id).restaurantId(restaurantId).kind(kind).position(0)
                .contentType(kind.equals("pdf") ? "application/pdf" : "image/png").sizeBytes(1).data(new byte[1]).build();
    }

    private RestaurantMenu menu(String mode, String manual) {
        return RestaurantMenu.builder().restaurantId(restaurantId).mode(mode).manualContent(manual).build();
    }

    private void restaurantExists() {
        when(restaurantRepository.existsById(restaurantId)).thenReturn(true);
    }

    private void noFiles() {
        when(fileRepository.findSummariesByRestaurantIdOrderByPositionAscCreatedAtAsc(restaurantId)).thenReturn(List.of());
    }

    // --- get

    @Test
    void get_whenNoMenuYet_returnsModeNoneAndLimits() {
        restaurantExists();
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());
        noFiles();

        MenuResponse response = menuService.get(restaurantId);

        assertThat(response.mode()).isEqualTo("none");
        assertThat(response.files()).isEmpty();
        assertThat(response.limits().imageMaxCount()).isEqualTo(8);
    }

    @Test
    void get_whenRestaurantMissing_throwsNotFound() {
        when(restaurantRepository.existsById(restaurantId)).thenReturn(false);

        assertThatThrownBy(() -> menuService.get(restaurantId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- upsert

    @Test
    void upsert_createsMenuOnFirstSaveAndStoresManualVerbatim() {
        restaurantExists();
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());
        when(menuRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        noFiles();

        MenuResponse response = menuService.upsert(restaurantId, new MenuRequest("manual",
                objectMapper.readTree("""
                        {"version":1,"sections":[{"name":"Entrees","items":[]}]}""")));

        assertThat(response.mode()).isEqualTo("manual");
        assertThat(response.manual().get("sections").get(0).get("name").asString()).isEqualTo("Entrees");
        assertThat(response.files()).isEmpty();
    }

    @Test
    void upsert_withoutManual_keepsExistingHandTypedMenu() {
        restaurantExists();
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.of(
                menu("manual", "{\"version\":1,\"sections\":[{\"name\":\"Plats\",\"items\":[]}]}")));
        when(menuRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        noFiles();

        MenuResponse response = menuService.upsert(restaurantId, new MenuRequest("none", null));

        assertThat(response.mode()).isEqualTo("none");
        assertThat(response.manual().get("sections").get(0).get("name").asString()).isEqualTo("Plats");
    }

    @Test
    void upsert_modeImagesWithoutAnyImage_isRejectedWith409() {
        restaurantExists();
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());
        when(fileRepository.countByRestaurantIdAndKind(restaurantId, "image")).thenReturn(0L);

        assertThatThrownBy(() -> menuService.upsert(restaurantId, new MenuRequest("images", null)))
                .isInstanceOf(MenuFileException.class)
                .satisfies(e -> assertThat(((MenuFileException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(menuRepository, never()).save(any());
    }

    @Test
    void upsert_modeManualWithEmptyContent_isRejectedWith409() {
        restaurantExists();
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> menuService.upsert(restaurantId, new MenuRequest("manual", objectMapper.readTree("{}"))))
                .isInstanceOf(MenuFileException.class)
                .satisfies(e -> assertThat(((MenuFileException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(menuRepository, never()).save(any());
    }

    @Test
    void upsert_manualThatIsNotAnObject_isRejectedWith400() {
        restaurantExists();

        assertThatThrownBy(() -> menuService.upsert(restaurantId, new MenuRequest("none", objectMapper.readTree("\"hello\""))))
                .isInstanceOf(MenuFileException.class)
                .satisfies(e -> assertThat(((MenuFileException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(menuRepository, never()).save(any());
    }

    // --- upload

    @Test
    void upload_pdf_replacesPreviousPdf() {
        restaurantExists();
        when(fileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());
        noFiles();

        menuService.upload(restaurantId, pdf(2048));

        verify(fileRepository).deleteByRestaurantIdAndKind(restaurantId, "pdf");
        verify(fileRepository).save(any(RestaurantMenuFile.class));
    }

    @Test
    void upload_image_appendsAfterTheLastPosition() {
        restaurantExists();
        when(fileRepository.findSummariesByRestaurantIdOrderByPositionAscCreatedAtAsc(restaurantId))
                .thenReturn(List.of(summary(UUID.randomUUID(), "image", 0), summary(UUID.randomUUID(), "image", 3)));
        when(fileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());

        menuService.upload(restaurantId, png());

        verify(fileRepository).save(org.mockito.ArgumentMatchers.argThat(
                f -> f.getKind().equals("image") && f.getPosition() == 4
                        && f.getContentType().equals("image/png") && f.getSizeBytes() == 64));
    }

    @Test
    void upload_ninthImage_isRejectedWith409() {
        restaurantExists();
        when(fileRepository.findSummariesByRestaurantIdOrderByPositionAscCreatedAtAsc(restaurantId))
                .thenReturn(java.util.stream.IntStream.range(0, 8)
                        .mapToObj(i -> summary(UUID.randomUUID(), "image", i)).toList());

        assertThatThrownBy(() -> menuService.upload(restaurantId, png()))
                .isInstanceOf(MenuFileException.class)
                .satisfies(e -> assertThat(((MenuFileException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(fileRepository, never()).save(any());
    }

    @Test
    void upload_unknownType_isRejectedWith415() {
        restaurantExists();

        assertThatThrownBy(() -> menuService.upload(restaurantId, "<svg xmlns='x'></svg>".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(MenuFileException.class)
                .satisfies(e -> assertThat(((MenuFileException) e).getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        verify(fileRepository, never()).save(any());
    }

    @Test
    void upload_pdfOverTenMegabytes_isRejectedWith413() {
        restaurantExists();

        assertThatThrownBy(() -> menuService.upload(restaurantId, pdf(10 * 1024 * 1024 + 1)))
                .isInstanceOf(MenuFileException.class)
                .satisfies(e -> assertThat(((MenuFileException) e).getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        verify(fileRepository, never()).save(any());
    }

    // --- deleteFile

    @Test
    void deleteFile_whenFileBelongsToAnotherRestaurant_throwsNotFound() {
        restaurantExists();
        UUID fileId = UUID.randomUUID();
        when(fileRepository.findSummaryByIdAndRestaurantId(fileId, restaurantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> menuService.deleteFile(restaurantId, fileId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(fileRepository, never()).deleteByIdAndRestaurantId(any(), any());
    }

    @Test
    void deleteFile_lastFileOfPublishedMode_resetsModeToNone() {
        restaurantExists();
        UUID pdfId = UUID.randomUUID();
        when(fileRepository.findSummaryByIdAndRestaurantId(pdfId, restaurantId)).thenReturn(Optional.of(summary(pdfId, "pdf", 0)));
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.of(menu("pdf", "{}")));
        when(fileRepository.countByRestaurantIdAndKind(restaurantId, "pdf")).thenReturn(0L);
        when(menuRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        noFiles();

        MenuResponse response = menuService.deleteFile(restaurantId, pdfId);

        verify(fileRepository).deleteByIdAndRestaurantId(pdfId, restaurantId);
        assertThat(response.mode()).isEqualTo("none");
    }

    @Test
    void deleteFile_middleImage_renumbersTheOthersWithoutGap() {
        restaurantExists();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(fileRepository.findSummaryByIdAndRestaurantId(b, restaurantId)).thenReturn(Optional.of(summary(b, "image", 1)));
        when(fileRepository.findSummariesByRestaurantIdOrderByPositionAscCreatedAtAsc(restaurantId))
                .thenReturn(List.of(summary(a, "image", 0), summary(c, "image", 2)));
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.of(menu("images", "{}")));
        when(fileRepository.countByRestaurantIdAndKind(restaurantId, "image")).thenReturn(2L);

        MenuResponse response = menuService.deleteFile(restaurantId, b);

        verify(fileRepository).updatePosition(c, restaurantId, 1);
        verify(fileRepository, never()).updatePosition(org.mockito.ArgumentMatchers.eq(a), any(), anyInt());
        assertThat(response.mode()).isEqualTo("images");
        verify(menuRepository, never()).save(any());
    }

    // --- reorder

    @Test
    void reorder_assignsPositionsInGivenOrder() {
        restaurantExists();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(fileRepository.findSummariesByRestaurantIdOrderByPositionAscCreatedAtAsc(restaurantId))
                .thenReturn(List.of(summary(a, "image", 0), summary(b, "image", 1)));
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());

        menuService.reorder(restaurantId, new MenuFileOrderRequest(List.of(b, a)));

        verify(fileRepository).updatePosition(b, restaurantId, 0);
        verify(fileRepository).updatePosition(a, restaurantId, 1);
    }

    @Test
    void reorder_partialList_isRejectedWith400() {
        restaurantExists();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(fileRepository.findSummariesByRestaurantIdOrderByPositionAscCreatedAtAsc(restaurantId))
                .thenReturn(List.of(summary(a, "image", 0), summary(b, "image", 1)));

        assertThatThrownBy(() -> menuService.reorder(restaurantId, new MenuFileOrderRequest(List.of(b))))
                .isInstanceOf(MenuFileException.class)
                .satisfies(e -> assertThat(((MenuFileException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(fileRepository, never()).updatePosition(any(), any(), anyInt());
    }

    @Test
    void reorder_unknownId_isRejectedWith400() {
        restaurantExists();
        when(fileRepository.findSummariesByRestaurantIdOrderByPositionAscCreatedAtAsc(restaurantId))
                .thenReturn(List.of(summary(UUID.randomUUID(), "image", 0)));

        assertThatThrownBy(() -> menuService.reorder(restaurantId, new MenuFileOrderRequest(List.of(UUID.randomUUID()))))
                .isInstanceOf(MenuFileException.class)
                .satisfies(e -> assertThat(((MenuFileException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(fileRepository, never()).updatePosition(any(), any(), anyInt());
    }

    // --- files

    @Test
    void getFile_whenFileBelongsToAnotherRestaurant_throwsNotFound() {
        UUID fileId = UUID.randomUUID();
        when(fileRepository.findByIdAndRestaurantId(fileId, restaurantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> menuService.getFile(restaurantId, fileId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getPublicFile_whenFileIsNotOfThePublishedMode_throwsNotFound() {
        UUID pdfId = UUID.randomUUID();
        when(fileRepository.findByIdAndRestaurantId(pdfId, restaurantId)).thenReturn(Optional.of(entity(pdfId, "pdf")));
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.of(menu("images", "{}")));

        assertThatThrownBy(() -> menuService.getPublicFile(restaurantId, pdfId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getPublicFile_whenPublished_returnsTheFile() {
        UUID imageId = UUID.randomUUID();
        when(fileRepository.findByIdAndRestaurantId(imageId, restaurantId)).thenReturn(Optional.of(entity(imageId, "image")));
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.of(menu("images", "{}")));

        assertThat(menuService.getPublicFile(restaurantId, imageId).getId()).isEqualTo(imageId);
    }

    // --- getPublic

    @Test
    void getPublic_exposesOnlyNameModeManualAndCurrentModeFiles() {
        Restaurant restaurant = Restaurant.builder().id(restaurantId).organizationId(UUID.randomUUID())
                .name("Chez Hikky").phoneNumber("+33100000000").build();
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.of(menu("images", "{}")));
        UUID imageId = UUID.randomUUID();
        when(fileRepository.findSummariesByRestaurantIdOrderByPositionAscCreatedAtAsc(restaurantId))
                .thenReturn(List.of(summary(UUID.randomUUID(), "pdf", 0), summary(imageId, "image", 0)));

        PublicMenuResponse response = menuService.getPublic(restaurantId);

        assertThat(response.restaurantName()).isEqualTo("Chez Hikky");
        assertThat(response.mode()).isEqualTo("images");
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).kind()).isEqualTo("image");
        assertThat(response.files().get(0).url())
                .isEqualTo("/api/public/restaurants/" + restaurantId + "/menu/files/" + imageId);
    }

    @Test
    void getPublic_whenRestaurantMissing_throwsNotFound() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> menuService.getPublic(restaurantId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
