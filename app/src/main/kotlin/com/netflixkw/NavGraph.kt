package com.netflixkw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.netflixkw.ui.components.BottomNavItem
import com.netflixkw.ui.components.NetflixBottomNavigationBar
import com.netflixkw.ui.screens.detail.MovieDetailScreen
import com.netflixkw.ui.screens.favorite.FavoriteScreen
import com.netflixkw.ui.screens.home.MovieListScreen
import com.netflixkw.ui.screens.search.SearchScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Search : Screen("search")
    object Favorite : Screen("favorite")
    object Detail : Screen("detail/{imdbId}") {
        fun createRoute(imdbId: String) = "detail/$imdbId"
    }
}

@Composable
fun NetflixKwNavGraph(
    navController: NavHostController = rememberNavController()
) {
    Scaffold(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            val currentRoute = currentDestination?.route

            val screens = listOf(Screen.Home, Screen.Search, Screen.Favorite)
            val showBottomBar = screens.any { it.route == currentRoute }

            if (showBottomBar) {
                NetflixBottomNavigationBar(
                    items = listOf(
                        BottomNavItem(Screen.Home.route, Icons.Rounded.Home, "Movies"),
                        BottomNavItem(Screen.Search.route, Icons.Rounded.Search, "Search"),
                        BottomNavItem(Screen.Favorite.route, Icons.Rounded.Favorite, "Favorites")
                    ),
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                MovieListScreen(onMovieClick = { imdbId ->
                    navController.navigate(Screen.Detail.createRoute(imdbId))
                })
            }
            composable(Screen.Search.route) {
                SearchScreen(onMovieClick = { imdbId ->
                    navController.navigate(Screen.Detail.createRoute(imdbId))
                })
            }
            composable(Screen.Favorite.route) {
                FavoriteScreen(onMovieClick = { imdbId ->
                    navController.navigate(Screen.Detail.createRoute(imdbId))
                })
            }
            composable(Screen.Detail.route) { backStackEntry ->
                val imdbId = backStackEntry.arguments?.getString("imdbId") ?: ""
                MovieDetailScreen(
                    imdbId = imdbId,
                    onBackClick = { navController.popBackStack() }
                )
            }
        }
    }
}
