package com.netflixkw.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.Warning

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.FavoriteBorder

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.netflixkw.ui.theme.badgeNumericTextStyle
import com.netflixkw.ui.theme.favoriteActive
import com.netflixkw.ui.theme.favoriteInactive
import com.netflixkw.ui.theme.glassScrim
import com.netflixkw.ui.theme.neutralBaseLayer
import com.netflixkw.ui.theme.primaryContainerDark
import com.netflixkw.ui.theme.ratingAmber
import com.netflixkw.ui.theme.statusError
import com.netflixkw.ui.theme.surfaceCard
import com.netflixkw.ui.theme.surfaceElevated
import com.netflixkw.ui.theme.textMuted
import com.netflixkw.ui.theme.textSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    imdbId: String,
    onBackClick: () -> Unit,
    viewModel: MovieDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(imdbId) {
        viewModel.getDetail(imdbId)
    }

    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = neutralBaseLayer,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (uiState is DetailUiState.Success) {
                        val movie = (uiState as DetailUiState.Success).movie
                        IconButton(
                            onClick = { viewModel.toggleFavorite() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (movie.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = "Toggle Favorite",
                                tint = if (movie.isFavorite) favoriteActive else favoriteInactive
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = glassScrim,
                    scrolledContainerColor = glassScrim
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()) {
            when (val state = uiState) {
                is DetailUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                is DetailUiState.Success -> {
                    val movie = state.movie
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // Header info
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .width(120.dp)
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceCard)
                            ) {
                                SubcomposeAsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(movie.posterUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = movie.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                    loading = {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Rounded.Warning,
                                                contentDescription = null,
                                                tint = textMuted
                                            )
                                        }
                                    },
                                    error = {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Rounded.Warning,
                                                contentDescription = null,
                                                tint = textMuted
                                            )
                                        }
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = movie.title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                primaryContainerDark.copy(alpha = 0.15f),
                                                RoundedCornerShape(50)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Rounded.Star,
                                                contentDescription = "Rating",
                                                tint = ratingAmber,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = movie.rating,
                                                style = badgeNumericTextStyle,
                                                color = ratingAmber
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(surfaceElevated, RoundedCornerShape(50))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = movie.year,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = favoriteInactive
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(surfaceElevated, RoundedCornerShape(50))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = movie.runtime,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = favoriteInactive
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                val genres = movie.genre.split(", ")

                                @Composable
                                fun GenreChip(text: String) {
                                    Box(
                                        modifier = Modifier
                                            .background(Color.Transparent, RoundedCornerShape(50))
                                            .padding(vertical = 4.dp, horizontal = 2.dp)
                                    ) {
                                        Text(
                                            text = text,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                Row {
                                    genres.take(3).forEachIndexed { index, genre ->
                                        GenreChip(genre)
                                        if (index < genres.size - 1 && index < 2) {
                                            Text(
                                                " • ",
                                                color = textMuted,
                                                modifier = Modifier.padding(
                                                    horizontal = 4.dp,
                                                    vertical = 4.dp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Synopsis",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = movie.plot,
                            style = MaterialTheme.typography.bodyMedium, // line height 20px
                            color = textSecondary
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        // Cast & Crew
                        Text(
                            text = "Cast",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = movie.actors,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textSecondary
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Director",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = movie.director,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textSecondary
                        )

                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }

                is DetailUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Error",
                            style = MaterialTheme.typography.headlineSmall,
                            color = statusError
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { viewModel.getDetail(imdbId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}
