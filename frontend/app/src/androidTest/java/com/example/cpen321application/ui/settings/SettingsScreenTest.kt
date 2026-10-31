package com.example.cpen321application.ui.settings

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.*
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import com.example.cpen321application.util.SessionManager
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import retrofit2.Response

class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalToken: String? = null
    private var saved: ProfileUpdate? = null
    private var loadCode = 200
    private val api = object : ApiService {
        override suspend fun loginWithGoogle(request: GoogleAuthRequest): Response<GoogleAuthResponse> = error("Unused")
        override suspend fun profile(authorization: String): Response<ProfileResponse> =
            if (loadCode == 200) Response.success(ProfileResponse(UserProfile("self", "jerry@example.com", "Jerry", "jerry")))
            else Response.error(loadCode, "{}".toResponseBody())
        override suspend fun updateProfile(authorization: String, profile: ProfileUpdate): Response<ProfileResponse> {
            saved = profile
            return Response.success(ProfileResponse(UserProfile("self", "jerry@example.com", profile.displayName, profile.username)))
        }
    }
    @Before fun prepare() {
        originalToken = SessionManager.getToken(context)
        SessionManager.saveToken(context, "settings-test")
    }
    @After fun restore() {
        originalToken?.let { SessionManager.saveToken(context, it) } ?: SessionManager.clearSession(context)
    }
    private fun render(onSignIn: () -> Unit = {}) {
        compose.setContent {
            var dark by remember { mutableStateOf(false) }
            CPEN321ApplicationTheme(darkTheme = dark) {
                Surface { SettingsScreen(api, dark, { dark = it }, onSignIn) }
            }
        }
    }
    @Test fun appearanceTogglesAndSignedOutAccountOffersLogin() {
        SessionManager.clearSession(context)
        var signInRequested = false
        render { signInRequested = true }
        compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Dark colors across the app").assertIsDisplayed()
        compose.onNodeWithText("Sign in with Google").performScrollTo().performClick()
        Assert.assertTrue(signInRequested)
    }
    @Test fun validatesAndSavesEditedProfile() {
        render()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("jerry@example.com").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Display name").performScrollTo().performTextReplacement("Jerry New")
        compose.onNodeWithText("Username").performScrollTo().performTextReplacement("a")
        compose.onNodeWithText("Save profile").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Username").performScrollTo().performTextReplacement("jerry_new")
        compose.onNodeWithText("Save profile").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved != null }
        compose.onNodeWithText("Profile saved.").performScrollTo().assertIsDisplayed()
        Assert.assertEquals(ProfileUpdate("Jerry New", "jerry_new"), saved)
    }
    @Test fun expiredSessionOffersReauthentication() {
        loadCode = 401
        var signInRequested = false
        render { signInRequested = true }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Sign in again").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Sign in again").performScrollTo().performClick()
        Assert.assertTrue(signInRequested)
    }
}
