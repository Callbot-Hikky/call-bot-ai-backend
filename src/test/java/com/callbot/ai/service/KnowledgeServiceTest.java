package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.dto.KnowledgeEntryRequest;
import com.callbot.ai.dto.KnowledgeSearchResponse;
import com.callbot.ai.embedding.EmbeddingClient;
import com.callbot.ai.embedding.EmbeddingException;
import com.callbot.ai.embedding.FakeEmbeddingClient;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.KnowledgeEntry;
import com.callbot.ai.repository.KnowledgeEntryRepository;
import com.callbot.ai.repository.RestaurantRepository;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    @Mock
    private KnowledgeEntryRepository entryRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private EmbeddingClient failingClient;

    private final UUID restaurantId = UUID.randomUUID();
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(entryRepository, restaurantRepository, new FakeEmbeddingClient(8));
    }

    @Test
    void create_storesTheEntryThenItsEmbedding() {
        when(entryRepository.saveAndFlush(any())).thenAnswer(i -> {
            KnowledgeEntry e = i.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        var response = service.create(restaurantId, new KnowledgeEntryRequest("  Terrasse ", " Vingt couverts. "));

        assertThat(response.title()).isEqualTo("Terrasse");
        assertThat(response.content()).isEqualTo("Vingt couverts.");
        assertThat(response.source()).isEqualTo("manual");
        verify(entryRepository).setEmbedding(eq(response.id()), anyString());
    }

    @Test
    void create_whenEmbeddingFails_writesNothing() {
        when(failingClient.embedDocuments(any())).thenThrow(new EmbeddingException("down"));
        KnowledgeService failing = new KnowledgeService(entryRepository, restaurantRepository, failingClient);

        assertThatThrownBy(() -> failing.create(restaurantId, new KnowledgeEntryRequest("t", "c")))
                .isInstanceOf(EmbeddingException.class);

        verify(entryRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_ofAnotherRestaurantsEntry_isNotFound() {
        UUID entryId = UUID.randomUUID();
        when(entryRepository.findByIdAndRestaurantId(entryId, restaurantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(restaurantId, entryId, new KnowledgeEntryRequest("t", "c")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void search_capsTheLimit_andTurnsDistanceIntoAScore() {
        when(entryRepository.findNearest(eq(restaurantId), anyString(), anyInt())).thenReturn(List.of(match(0.25)));

        KnowledgeSearchResponse response = service.search(restaurantId, "une terrasse ?", 50);

        verify(entryRepository).findNearest(eq(restaurantId), anyString(), eq(KnowledgeService.MAX_LIMIT));
        assertThat(response.matches()).hasSize(1);
        assertThat(response.matches().get(0).score()).isEqualTo(0.75);
    }

    @Test
    void vectorLiteral_isPgvectorTextForm() {
        assertThat(KnowledgeService.toVectorLiteral(new float[] {0.5f, 0f, 1f})).isEqualTo("[0.5,0.0,1.0]");
    }

    private static KnowledgeEntryRepository.Match match(double distance) {
        return new KnowledgeEntryRepository.Match() {
            public UUID getId() {
                return UUID.randomUUID();
            }

            public String getTitle() {
                return "Terrasse";
            }

            public String getContent() {
                return "Vingt couverts.";
            }

            public Double getDistance() {
                return distance;
            }
        };
    }
}
