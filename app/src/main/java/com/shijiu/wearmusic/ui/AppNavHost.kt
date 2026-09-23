package com.shijiu.wearmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.ui.about.AboutScreen
import com.shijiu.wearmusic.ui.account.AccountScreen
import com.shijiu.wearmusic.ui.album.AlbumDetailScreen
import com.shijiu.wearmusic.ui.artist.ArtistScreen
import com.shijiu.wearmusic.ui.comments.CommentsScreen
import com.shijiu.wearmusic.ui.cloud.CloudScreen
import com.shijiu.wearmusic.ui.daily.DailyScreen
import com.shijiu.wearmusic.ui.dj.DjDetailScreen
import com.shijiu.wearmusic.ui.fm.FmScreen
import com.shijiu.wearmusic.ui.heart.HeartScreen
import com.shijiu.wearmusic.ui.home.HomeScreen
import com.shijiu.wearmusic.ui.lists.PersonalizedScreen
import com.shijiu.wearmusic.ui.lists.RadarScreen
import com.shijiu.wearmusic.ui.lists.ToplistScreen
import com.shijiu.wearmusic.ui.login.LoginScreen
import com.shijiu.wearmusic.ui.mine.MineScreen
import com.shijiu.wearmusic.ui.player.LyricsScreen
import com.shijiu.wearmusic.ui.player.PlayerScreen
import com.shijiu.wearmusic.ui.playlist.AddToPlaylistScreen
import com.shijiu.wearmusic.ui.playlist.CreatePlaylistScreen
import com.shijiu.wearmusic.ui.playlist.PlaylistDetailScreen
import com.shijiu.wearmusic.ui.playlist.PlaylistEditScreen
import com.shijiu.wearmusic.ui.search.SearchScreen

@Composable
fun AppNavHost() {
    val navController = rememberSwipeDismissableNavController()

    // 任意列表点播后自动打开播放页（launchSingleTop 防止重复压栈）
    LaunchedEffect(Unit) {
        ServiceLocator.container.playbackManager.openPlayerRequests.collect {
            navController.navigate(Routes.PLAYER) { launchSingleTop = true }
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) { HomeScreen(navController) }
        composable(Routes.LOGIN) { LoginScreen(navController) }
        composable(Routes.ACCOUNT) { AccountScreen(navController) }
        composable(Routes.PLAYER) { PlayerScreen(navController) }
        composable(Routes.LYRICS) { LyricsScreen() }
        composable(Routes.DAILY) { DailyScreen(navController) }
        composable(Routes.FM) { FmScreen(navController) }
        composable(Routes.HEART) { HeartScreen(navController) }
        composable(Routes.RADAR) { RadarScreen(navController) }
        composable(Routes.PERSONALIZED) { PersonalizedScreen(navController) }
        composable(Routes.TOPLIST) { ToplistScreen(navController) }
        composable(Routes.SEARCH) { SearchScreen(navController) }
        composable(Routes.CLOUD) { CloudScreen(navController) }
        composable(Routes.MINE) { MineScreen(navController) }
        composable(Routes.CREATE_PLAYLIST) { CreatePlaylistScreen(navController) }
        composable(Routes.ABOUT) { AboutScreen() }

        composable(Routes.PLAYLIST) { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull()
                ?: return@composable
            PlaylistDetailScreen(navController, id)
        }
        composable(Routes.PLAYLIST_EDIT) { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull()
                ?: return@composable
            PlaylistEditScreen(navController, id)
        }
        composable(Routes.ADD_TO_PLAYLIST) { entry ->
            val songId = entry.arguments?.getString("songId")?.toLongOrNull()
                ?: return@composable
            AddToPlaylistScreen(navController, songId)
        }
        composable(Routes.ALBUM) { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull()
                ?: return@composable
            AlbumDetailScreen(navController, id)
        }
        composable(Routes.ARTIST) { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull()
                ?: return@composable
            ArtistScreen(navController, id)
        }
        composable(Routes.DJ) { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull()
                ?: return@composable
            DjDetailScreen(navController, id)
        }
        composable(Routes.COMMENTS) { entry ->
            val type = entry.arguments?.getString("type")?.toIntOrNull() ?: 0
            val id = entry.arguments?.getString("id")?.toLongOrNull()
                ?: return@composable
            val title = entry.arguments?.getString("title").orEmpty()
            CommentsScreen(navController, type, id, title)
        }
    }
}
