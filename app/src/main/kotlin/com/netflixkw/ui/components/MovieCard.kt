package com.netflixkw.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Warning

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.netflixkw.core.domain.model.Movie
import com.netflixkw.ui.theme.borderSubtle
import com.netflixkw.ui.theme.favoriteActive
import com.netflixkw.ui.theme.favoriteInactive
import com.netflixkw.ui.theme.surfaceCard
import com.netflixkw.ui.theme.surfaceDefault
import com.netflixkw.ui.theme.surfaceElevated
import com.netflixkw.ui.theme.textMuted

@Composable
fun MovieCard(
    movie: Movie,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(surfaceDefault) // #121826
            .border(1.dp, borderSubtle, RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Poster
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .aspectRatio(2f / 3f)
                    .background(surfaceCard, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
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
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = textMuted)
                        }
                    },
                    error = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = textMuted)
                        }
                    }
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Meta Column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = movie.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                // Badges
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(surfaceElevated, RoundedCornerShape(50)) // subtle slate tag
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = movie.year,
                            style = MaterialTheme.typography.labelSmall,
                            color = favoriteInactive // #94A3B8
                        )
                    }
                }
            }
            
            // Action
            IconButton(
                onClick = onFavoriteClick,
                modifier = Modifier
                    .padding(8.dp)
                    .size(48.dp)
            ) {
                Icon(
                    imageVector = if (movie.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (movie.isFavorite) favoriteActive else favoriteInactive
                )
            }
        }
    }
}
