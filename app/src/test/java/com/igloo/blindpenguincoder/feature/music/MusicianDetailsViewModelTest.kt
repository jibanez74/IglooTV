package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.musicianDetailsJson
import com.igloo.blindpenguincoder.data.repository.musicianJson
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicianDetailsViewModelTest {

    private val viewModels = mutableListOf<MusicianDetailsViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.close() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(http: TestHttp) =
        MusicianDetailsViewModel(http.musicRepository).also { viewModels += it }

    private fun TestScope.http(
        respond: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(musicianDetailsJson()) },
    ) = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
        check(request.url.encodedPath.startsWith("/api/music/musicians/")) {
            "Unrouted path: ${request.url.encodedPath}"
        }
        respond(request)
    }

    @Test
    fun `nothing loads until a musician is opened, and open loads that musician`() = runTest {
        var requests = 0
        val model = viewModel(http { requests += 1; jsonResponse(musicianDetailsJson(musician = musicianJson(id = 4))) })
        assertEquals(0, requests)
        assertNull(model.uiState.value.openMusicianId)

        model.open(4)

        assertEquals(1, requests)
        assertEquals(4L, model.uiState.value.openMusicianId)
        val loaded = model.uiState.value.details as MusicianDetailsState.Loaded
        assertEquals("The Beatles", loaded.musician.name)
    }

    @Test
    fun `a failed open is an error and Retry re-reads`() = runTest {
        var fail = true
        val model = viewModel(
            http {
                if (fail) jsonResponse("""{"error":true,"message":"musician not found"}""", HttpStatusCode.NotFound)
                else jsonResponse(musicianDetailsJson())
            },
        )

        model.open(4)
        val error = model.uiState.value.details as MusicianDetailsState.Error
        assertEquals("musician not found", error.message)

        fail = false
        model.retry()
        assertTrue(model.uiState.value.details is MusicianDetailsState.Loaded)
    }

    @Test
    fun `a background refresh failure keeps the loaded page`() = runTest {
        var fail = false
        val model = viewModel(
            http {
                if (fail) jsonResponse("""{"error":true,"message":"nope"}""", HttpStatusCode.InternalServerError)
                else jsonResponse(musicianDetailsJson())
            },
        )
        model.open(4)

        fail = true
        model.refresh()

        assertTrue(model.uiState.value.details is MusicianDetailsState.Loaded)
    }

    @Test
    fun `close clears the overlay and a late response cannot reopen it`() = runTest {
        val model = viewModel(http())
        model.open(4)

        model.close()

        assertNull(model.uiState.value.openMusicianId)
        assertTrue(model.uiState.value.details is MusicianDetailsState.Loading)
        model.refresh()
        assertNull(model.uiState.value.openMusicianId)
    }
}
