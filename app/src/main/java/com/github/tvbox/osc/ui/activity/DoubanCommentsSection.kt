package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvshell.app.R
import com.github.tvbox.osc.util.DoubanApi
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.launch

private sealed interface DoubanCommentsState {
    data object Loading : DoubanCommentsState

    data object Empty : DoubanCommentsState

    data object NoCookie : DoubanCommentsState

    data object AuthExpired : DoubanCommentsState

    data class Ready(
        val doubanId: String,
        val cookie: String,
        val rating: DoubanApi.DoubanRating?,
        val comments: List<DoubanApi.DoubanComment>,
        val hasMore: Boolean,
        val nextStart: Int,
        val loadingMore: Boolean,
    ) : DoubanCommentsState
}

@Composable
internal fun DoubanCommentsSection(doubanId: String?, title: String?) {
    val context = LocalContext.current
    if (!DoubanApi.hasCookie()) {
        DoubanHintRow(
            text = stringResource(R.string.douban_no_cookie_hint),
            onClick = { ConfigManageActivity.start(context) },
        )
        return
    }
    var state by remember(doubanId, title) {
        mutableStateOf<DoubanCommentsState>(DoubanCommentsState.Loading)
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(doubanId, title) {
        state = DoubanCommentsState.Loading
        val cookie = DoubanApi.currentCookie()
        if (cookie.isBlank()) {
            state = DoubanCommentsState.NoCookie
            return@LaunchedEffect
        }
        state = try {
            val id = doubanId?.takeIf { it.isNotBlank() }
                ?: DoubanApi.resolveDoubanId(title.orEmpty(), cookie)
            if (id.isNullOrBlank()) {
                DoubanCommentsState.Empty
            } else {
                val rating = DoubanApi.rating(id, cookie)
                val page = DoubanApi.comments(id, cookie, 0)
                if (page.comments.isEmpty()) {
                    DoubanCommentsState.Empty
                } else {
                    DoubanCommentsState.Ready(
                        doubanId = id,
                        cookie = cookie,
                        rating = rating,
                        comments = page.comments,
                        hasMore = page.hasMore,
                        nextStart = 20,
                        loadingMore = false,
                    )
                }
            }
        } catch (e: DoubanApi.DoubanAuthException) {
            DoubanCommentsState.AuthExpired
        } catch (e: Exception) {
            LOG.e("DoubanComments", e)
            DoubanCommentsState.Empty
        }
    }
    when (val current = state) {
        DoubanCommentsState.Loading, DoubanCommentsState.Empty -> return
        DoubanCommentsState.NoCookie -> DoubanHintRow(
            text = stringResource(R.string.douban_no_cookie_hint),
            onClick = { ConfigManageActivity.start(context) },
        )
        DoubanCommentsState.AuthExpired -> DoubanHintRow(
            text = stringResource(R.string.douban_auth_expired),
            onClick = { ConfigManageActivity.start(context) },
        )
        is DoubanCommentsState.Ready -> DoubanCommentsBlock(
            state = current,
            onLoadMore = {
                if (current.loadingMore || !current.hasMore) return@DoubanCommentsBlock
                scope.launch {
                    state = current.copy(loadingMore = true)
                    state = try {
                        DoubanApi.pageDelay()
                        val page = DoubanApi.comments(current.doubanId, current.cookie, current.nextStart)
                        current.copy(
                            comments = current.comments + page.comments,
                            hasMore = page.hasMore,
                            nextStart = current.nextStart + 20,
                            loadingMore = false,
                        )
                    } catch (e: DoubanApi.DoubanAuthException) {
                        DoubanCommentsState.AuthExpired
                    } catch (e: Exception) {
                        LOG.e("DoubanComments", e)
                        current.copy(loadingMore = false)
                    }
                }
            },
        )
    }
}

@Composable
private fun DoubanHintRow(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = detailCardColor(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun DoubanCommentsBlock(
    state: DoubanCommentsState.Ready,
    onLoadMore: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_config_douban))
            Text(
                text = stringResource(R.string.douban_comments_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
            state.rating?.let { rating ->
                Text(
                    text = stringResource(R.string.douban_rating_head, rating.score, rating.count),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (comment in state.comments) {
                DoubanCommentCard(comment)
            }
        }
        if (state.hasMore) {
            TextButton(
                onClick = onLoadMore,
                enabled = !state.loadingMore,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(
                        if (state.loadingMore) R.string.douban_loading else R.string.douban_load_more,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DoubanCommentCard(comment: DoubanApi.DoubanComment) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = detailCardColor(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.nick.ifEmpty { stringResource(R.string.douban_anonymous) },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (comment.stars in 1..5) {
                    Text(
                        text = "★".repeat(comment.stars),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (comment.time.isNotEmpty()) {
                    Text(
                        text = comment.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (comment.content.isNotEmpty()) {
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (comment.votes > 0) {
                Text(
                    text = stringResource(R.string.douban_votes, comment.votes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
