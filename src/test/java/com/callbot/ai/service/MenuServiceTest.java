package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private void restaurantExists() {
        when(restaurantRepository.existsById(restaurantId)).thenReturn(true);
    }

    @Test
    void get_whenNoMenuYet_returnsModeNoneAndLimits() {
        restaurantExists();
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());
        when(fileRepository.findByRestaurantIdOrderByPositionAsc(restaurantId)).thenReturn(List.of());

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

    @Test
    void upsert_createsMenuOnFirstSaveAndStoresManualVerbatim() {
        restaurantExists();
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());
        when(menuRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(fileRepository.findByRestaurantIdOrderByPositionAsc(restaurantId)).thenReturn(List.of());

        MenuResponse response = menuService.upsert(restaurantId, new MenuRequest("manual",
                objectMapper.readTree("""
                        {"version":1,"sections":[{"name":"Entrees","items":[]}]}""")));

        assertThat(response.mode()).isEqualTo("manual");
        assertThat(response.manual().get("sections").get(0).get("name").asString()).isEqualTo("Entrees");
    }

    @Test
    void upsert_withNullManual_storesEmptyObject() {
        restaurantExists();
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());
        when(menuRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(fileRepository.findByRestaurantIdOrderByPositionAsc(restaurantId)).thenReturn(List.of());

        MenuResponse response = menuService.upsert(restaurantId, new MenuRequest("none", null));

        assertThat(response.manual().isEmpty()).isTrue();
    }

    @Test
    void upload_pdf_replacesPreviousPdf() {
        restaurantExists();
        when(fileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(fileRepository.findByRestaurantIdOrderByPositionAsc(restaurantId)).thenReturn(List.of());
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());

        menuService.upload(restaurantId, pdf(2048));

        verify(fileRepository).deleteByRestaurantIdAndKind(restaurantId, "pdf");
        verify(fileRepository).save(any(RestaurantMenuFile.class));
    }

    @Test
    void upload_image_appendsAtEndOfOrder() {
        restaurantExists();
        when(fileRepository.countByRestaurantIdAndKind(restaurantId, "image")).thenReturn(2L);
        when(fileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(fileRepository.findByRestaurantIdOrderByPositionAsc(restaurantId)).thenReturn(List.of());
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());

        menuService.upload(restaurantId, png());

        verify(fileRepository).save(org.mockito.ArgumentMatchers.argThat(
                f -> f.getKind().equals("image") && f.getPosition() == 2
                        && f.getContentType().equals("image/png") && f.getSizeBytes() == 64));
    }

    @Test
    void upload_ninthImage_isRejectedWith409() {
        restaurantExists();
        when(fileRepository.countByRestaurantIdAndKind(restaurantId, "image")).thenReturn(8L);

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

    @Test
    void deleteFile_whenFileBelongsToAnotherRestaurant_throwsNotFound() {
        restaurantExists();
        UUID fileId = UUID.randomUUID();
        when(fileRepository.findByIdAndRestaurantId(fileId, restaurantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> menuService.deleteFile(restaurantId, fileId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reorder_assignsPositionsInGivenOrder() {
        restaurantExists();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        RestaurantMenuFile fileA = RestaurantMenuFile.builder().id(a).restaurantId(restaurantId).kind("image").position(0).contentType("image/png").sizeBytes(1).data(new byte[1]).build();
        RestaurantMenuFile fileB = RestaurantMenuFile.builder().id(b).restaurantId(restaurantId).kind("image").position(1).contentType("image/png").sizeBytes(1).data(new byte[1]).build();
        when(fileRepository.findByRestaurantIdOrderByPositionAsc(restaurantId)).thenReturn(List.of(fileA, fileB));
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());

        menuService.reorder(restaurantId, new MenuFileOrderRequest(List.of(b, a)));

        assertThat(fileB.getPosition()).isEqualTo(0);
        assertThat(fileA.getPosition()).isEqualTo(1);
        verify(fileRepository).saveAll(List.of(fileA, fileB));
    }

    @Test
    void getPublic_exposesOnlyNameModeManualAndCurrentModeFiles() {
        Restaurant restaurant = Restaurant.builder().id(restaurantId).organizationId(UUID.randomUUID())
                .name("Chez Hikky").phoneNumber("+33100000000").build();
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.of(
                RestaurantMenu.builder().restaurantId(restaurantId).mode("images").manualContent("{}").build()));
        RestaurantMenuFile pdfFile = RestaurantMenuFile.builder().id(UUID.randomUUID()).restaurantId(restaurantId).kind("pdf").position(0).contentType("application/pdf").sizeBytes(1).data(new byte[1]).build();
        RestaurantMenuFile image = RestaurantMenuFile.builder().id(UUID.randomUUID()).restaurantId(restaurantId).kind("image").position(0).contentType("image/png").sizeBytes(1).data(new byte[1]).build();
        when(fileRepository.findByRestaurantIdOrderByPositionAsc(restaurantId)).thenReturn(List.of(pdfFile, image));

        PublicMenuResponse response = menuService.getPublic(restaurantId);

        assertThat(response.restaurantName()).isEqualTo("Chez Hikky");
        assertThat(response.mode()).isEqualTo("images");
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).kind()).isEqualTo("image");
        assertThat(response.files().get(0).url())
                .isEqualTo("/api/public/restaurants/" + restaurantId + "/menu/files/" + image.getId());
    }

    @Test
    void getPublic_whenRestaurantMissing_throwsNotFound() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> menuService.getPublic(restaurantId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
