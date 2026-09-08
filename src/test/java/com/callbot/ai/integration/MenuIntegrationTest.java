package com.callbot.ai.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

class MenuIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** Inscrit un utilisateur (qui recoit sa propre organisation) et renvoie son jeton. */
    private String registerAndGetToken(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"password123"}""".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    /** Cree un restaurant dans l'organisation de l'utilisateur du jeton. */
    private String createOwnedRestaurant(String token, String name, String phone) throws Exception {
        String me = mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String organizationId = JsonPath.read(me, "$.organizationId");

        String created = mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"%s","phoneNumber":"%s"}"""
                        .formatted(organizationId, name, phone)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    private static byte[] pngBytes() {
        byte[] bytes = new byte[256];
        byte[] magic = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }

    @Test
    void manualMenuIsSavedThenReadPubliclyWithoutAuth() throws Exception {
        String token = registerAndGetToken("menu-owner@example.com");
        String restaurantId = createOwnedRestaurant(token, "Chez Menu", "+33100000101");

        mockMvc.perform(get("/api/public/restaurants/" + restaurantId + "/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("none"))
                .andExpect(jsonPath("$.restaurantName").value("Chez Menu"));

        mockMvc.perform(put("/api/restaurants/" + restaurantId + "/menu")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mode":"manual","manual":{"version":1,"sections":[{"name":"Plats","items":[{"name":"Tajine","price":"18.00"}]}]}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("manual"));

        mockMvc.perform(get("/api/public/restaurants/" + restaurantId + "/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("manual"))
                .andExpect(jsonPath("$.manual.sections[0].items[0].name").value("Tajine"))
                .andExpect(jsonPath("$.phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.address").doesNotExist());
    }

    @Test
    void uploadedImageIsServedPubliclyWithDetectedContentType() throws Exception {
        String token = registerAndGetToken("menu-images@example.com");
        String restaurantId = createOwnedRestaurant(token, "Chez Images", "+33100000102");

        // Le client annonce un type mensonger : le serveur stocke le type detecte.
        MockMultipartFile image = new MockMultipartFile("file", "carte.jpg", "image/jpeg", pngBytes());
        String menu = mockMvc.perform(multipart("/api/restaurants/" + restaurantId + "/menu/files")
                .file(image)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].kind").value("image"))
                .andExpect(jsonPath("$.files[0].contentType").value("image/png"))
                .andReturn().getResponse().getContentAsString();
        String fileUrl = JsonPath.read(menu, "$.files[0].url");

        mockMvc.perform(put("/api/restaurants/" + restaurantId + "/menu")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mode":"images"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get(fileUrl))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(pngBytes()));
    }

    @Test
    void svgUploadIsRejectedWith415() throws Exception {
        String token = registerAndGetToken("menu-svg@example.com");
        String restaurantId = createOwnedRestaurant(token, "Chez Svg", "+33100000103");
        MockMultipartFile svg = new MockMultipartFile("file", "x.svg", "image/svg+xml",
                "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes());

        mockMvc.perform(multipart("/api/restaurants/" + restaurantId + "/menu/files")
                .file(svg)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("unsupported_file_type"));
    }

    @Test
    void otherOrganizationCannotEditTheMenu() throws Exception {
        String ownerToken = registerAndGetToken("menu-owner2@example.com");
        String restaurantId = createOwnedRestaurant(ownerToken, "Chez Proprio", "+33100000104");
        String intruderToken = registerAndGetToken("menu-intruder@example.com");

        mockMvc.perform(put("/api/restaurants/" + restaurantId + "/menu")
                .header("Authorization", "Bearer " + intruderToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mode":"none"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void adminEndpointWithoutSessionReturns401() throws Exception {
        String token = registerAndGetToken("menu-anon@example.com");
        String restaurantId = createOwnedRestaurant(token, "Chez Anon", "+33100000105");

        mockMvc.perform(get("/api/restaurants/" + restaurantId + "/menu"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicMenuOfUnknownRestaurantReturns404() throws Exception {
        mockMvc.perform(get("/api/public/restaurants/00000000-0000-0000-0000-000000000000/menu"))
                .andExpect(status().isNotFound());
    }
}
