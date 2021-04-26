/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tv.reference.watchnext

import android.annotation.SuppressLint
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.Context
import android.database.Cursor
import android.media.tv.TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
import android.media.tv.TvContract.WatchNextPrograms.TYPE_MOVIE
import android.media.tv.TvContract.WatchNextPrograms.TYPE_TV_EPISODE
import android.media.tv.TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
import android.media.tv.TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.tvprovider.media.tv.WatchNextProgram.Builder
import androidx.tvprovider.media.tv.WatchNextProgram.fromCursor
import com.android.tv.reference.R
import com.android.tv.reference.repository.VideoRepository
import com.android.tv.reference.shared.datamodel.Video
import com.android.tv.reference.shared.datamodel.VideoType
import timber.log.Timber
import java.time.Duration
import java.util.concurrent.TimeUnit


/**
 * Helper class that simplifies interactions with the ATV home screen.
 *
 * To view the Watch Next row directly through adb, run the following command (assumes `adb root`):
 * ```
 * adb shell "sqlite3 -header -csv /data/data/com.android.providers.tv/databases/tv.db \"SELECT * FROM watch_next_programs where package_name='com.android.tv.reference';\"";
 * ```
 * For example, this query returns the title and position of the Watch Next entries, which is useful
 * for debugging updates to a particular entry.
 * ```
 * adb shell "sqlite3 -header -csv /data/data/com.android.providers.tv/databases/tv.db \"SELECT title, last_playback_position_millis FROM watch_next_programs where package_name='com.android.tv.reference';\"";
 * ```
 */
object WatchNextHelper {

    // Values to approximate if video has started.
    private const val WATCH_NEXT_STARTED_MIN_PERCENTAGE = 0.03
    private const val WATCH_NEXT_STARTED_MIN_MINUTES = 2L

    // MetaData sent from Playback to process Watch Next operations.
    internal const val VIDEO_ID = "VIDEO_ID"
    internal const val CURRENT_POSITION = "CURRENT_POSITION"
    internal const val DURATION = "DURATION"
    internal const val PLAYER_STATE = "PLAYER_STATE"

    // TODO: add sealed class/enum if more states are required
    // as a part of a performance/tech debt optimization
    internal const val PLAY_STATE_PAUSED = "STATE_PAUSED"
    internal const val PLAY_STATE_ENDED = "STATE_ENDED"

    /**
     * Add all relevant metadata which will be displayed on Watch Next Channel.
     */
    private fun setBuilderMetadata(
        builder: Builder,
        video: Video,
        watchNextType: Int,
        watchPosition: Int,
        type: Int,
        duration: Duration,
        context: Context
    ): Builder {
        // TODO: Step 1.1 - Set video metadata for WatchNextProgram.
        builder.setType(type)
            .setWatchNextType(watchNextType)
            .setLastPlaybackPositionMillis(watchPosition)
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .setTitle(video.name)
            .setDurationMillis(duration.toMillis().toInt())
            .setPreviewVideoUri(Uri.parse(video.videoUri))
            .setDescription(video.description)
            .setPosterArtUri(Uri.parse(video.thumbnailUri))
            // Intent uri used to deep link video when user clicks on Watch Next item.
            .setIntentUri(Uri.parse(video.uri))
            /* The internalProviderId attribute must match the internal ID you provide in the
            Media PlaybackStateCompat.Actions feed. This allows Android TV to reconcile
            the asset more effectively and provides a high-confidence feature to users.
            Refer https://developer.android.com/training/tv/discovery/watch-next-programs?hl=tr*/
            .setInternalProviderId(video.id)
            // Use the contentId to recognize same content across different channels.
            .setContentId(video.id)

        if (type == TYPE_TV_EPISODE) {
            builder.setEpisodeNumber(video.episodeNumber.toInt())
                .setSeasonNumber(video.seasonNumber.toInt())
                // User TV series name and season number to generate a fake season name.
                .setSeasonTitle(context.getString(
                    R.string.season, video.category, video.seasonNumber))
                // Use the name of the video as the episode name.
                .setEpisodeTitle(video.name)
                // Use TV series name as the tile, in this sample,
                // we use category as a fake TV series.
                .setTitle(video.category)
        }
        // End of Step 1.1
        return builder
    }

    /**
     * The user has "started" a video if they've watched more than 3% or 2 minutes,
     * whichever timestamp is earlier.
     * https://developer.android.com/training/tv/discovery/guidelines-app-developers
     */
    internal fun hasVideoStarted(duration: Duration, currentPosition: Int): Boolean {
        // TODO: Step 4.2 - Add to Watch Next channel only after video started.
        return true
        // End of step 4.2
    }

    /**
     * Retrieve all programs in Watch Next row.
     */
    @SuppressLint("RestrictedApi")
    // Suppress RestrictedApi due to https://issuetracker.google.com/138150076
    internal fun getWatchNextPrograms(context: Context): List<WatchNextProgram> {
        val programs: MutableList<WatchNextProgram> = mutableListOf()
        val cursor = context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            WatchNextProgram.PROJECTION,
            /* selection = */ null,
            /* selectionArgs = */ null,
            /* sortOrder= */ null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                do {
                    programs.add(fromCursor(cursor))
                } while (it.moveToNext())
            }
        }
        return programs
    }

    /**
     * Add unfinished program to Watch Next.
     * Update the playback position if program already exists in Watch Next channel.
     */
    @SuppressLint("RestrictedApi")
    // Suppress RestrictedApi due to https://issuetracker.google.com/138150076
    @Synchronized
    internal fun insertOrUpdateVideoToWatchNext(
        video: Video,
        watchPosition: Int,
        watchNextType: Int,
        context: Context
    ): Long {

        if (video.videoType != VideoType.MOVIE && video.videoType != VideoType.EPISODE) {
            throw IllegalArgumentException(
                "Watch Next is not supported for Video Type: ${video?.videoType}")
        }

        var programId = 0L
        // If we already have a program with this ID, use it as a base for updated program.
        // Avoid Duplicates.

        val existingProgram = getWatchNextProgramByVideoId(video.id, context)
        Timber.v(
            "insertOrUpdateToWatchNext, existingProgram = $existingProgram ,videoid = ${video.id}"
        )
        // If program exists,create builder with existing program.
        val programBuilder = existingProgram
            ?.let {
                Builder(it)
            } ?: Builder()

        val videoType = if(video.videoType == VideoType.MOVIE) {
            TYPE_MOVIE
        } else {
            TYPE_TV_EPISODE
        }

        val updatedBuilder = setBuilderMetadata(
            programBuilder,
            video,
            watchNextType,
            watchPosition,
            videoType,
            video.duration(),
            context
        )

        // Build the program with all the metadata
        val updatedProgram = updatedBuilder.build()

        // If the program is already in the Watch Next row, update it
        if (existingProgram != null) {
            // TODO: Step 1.3 - Update program in the Watch Next channel.
            programId = existingProgram.id
            PreviewChannelHelper(context).updateWatchNextProgram(updatedProgram, programId)
            // End of step 1.3
            Timber.v("Updated program in Watch Next row: ${updatedProgram.title}")
        }
        // Otherwise build the program and insert it into the channel
        else {
            // TODO: Step 1.2 - Insert program to Watch Next channel.
            try {
                programId = PreviewChannelHelper(context)
                    .publishWatchNextProgram(updatedProgram)
                Timber.v("Added New program to Watch Next row: ${updatedProgram.title}")
            } catch (exc: IllegalArgumentException) {
                Timber.e(
                    exc, "Unable to add program to Watch Next row. ${exc.localizedMessage}"
                )
                exc.printStackTrace()
            }
            // End of step 1.2
        }

        Timber.v("Final added/updated programId = $programId")
        return programId
    }

    /**
     *  Remove a Video object from the Watch Next row,
     *  Typically after user has finished watching the video.
     *  Returns the number of rows deleted or null if delete fails
     */
    @Synchronized
    @SuppressLint("RestrictedApi")
    // Suppress RestrictedApi due to https://issuetracker.google.com/138150076
    fun removeVideoFromWatchNext(context: Context, video: Video): Uri? {
        Timber.v("Trying to Removing content from Watch Next: ${video.name}")

        // Find the program with the matching ID for our metadata.
        // TODO: Step 2.1 - Remove program from Watch Next channel.
        return null
        // End of step 2.1
    }

    @Synchronized
    @SuppressLint("RestrictedApi")
    // Suppress RestrictedApi due to https://issuetracker.google.com/138150076
    fun removeVideosFromWatchNext(context: Context, videos: List<Video>) {
        // Find the program with the matching ID for our metadata.
        // TODO: Step 2.2 - Use batch operation to remove multiple programs from Watch Next channel.

        // End of step 2.2
    }

    /**
     * Query the Watch Next list and find the program with given videoId.
     * Return null if not found.
     */
    @Synchronized
    private fun getWatchNextProgramByVideoId(id: String, context: Context): WatchNextProgram? {
        return findFirstWatchNextProgram(context) { cursor ->
            (cursor.getString(cursor.getColumnIndex(COLUMN_INTERNAL_PROVIDER_ID)) == id)
        }
    }

    @Synchronized
    @SuppressLint("RestrictedApi")
    // Suppress RestrictedApi due to https://issuetracker.google.com/138150076
    private fun getWatchNextProgramByVideoIds(ids: List<String>, context: Context):
            List<WatchNextProgram> {
        return getWatchNextPrograms(context).filter {
            ids.contains(it.internalProviderId)
        }
    }

    /**
     * Find the Watch Next program for given id.
     * Returns the first instance available.
     */
    @SuppressLint("RestrictedApi")
    // Suppress RestrictedApi due to https://issuetracker.google.com/138150076
    internal fun findFirstWatchNextProgram(context: Context, predicate: (Cursor) -> Boolean):
            WatchNextProgram? {

        val cursor = context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            WatchNextProgram.PROJECTION,
            /* selection = */ null,
            /* selectionArgs = */ null,
            /* sortOrder= */ null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                do {
                    if (predicate(cursor)) {
                        return fromCursor(cursor)
                    }
                } while (it.moveToNext())
            }
        }
        return null
    }

    /**
     *  Returns a list of videos which is visible on Watch Next row.
     */
    @SuppressLint("RestrictedApi")
    internal fun filterWatchNextVideos(videos: List<Video>, context: Context): List<Video> {
        val watchedPrograms = getWatchNextProgramByVideoIds(videos.map { it.id }, context)
        val watchedVideosIds = watchedPrograms.map { it.internalProviderId }
        return videos.filter { watchedVideosIds.contains(it.id) }
    }

    /**
     * Handle operations for Watch Next channel for video type 'Movie'.
     */
    internal fun handleWatchNextForMovie(
        video: Video,
        watchPosition: Int,
        state: String?,
        context: Context
    ) {
        Timber.v("Adding/remove movie to Watch Next. Video Name: ${video.name}")

        when {
            // If movie has finished, remove from Watch Next channel.
            (state == PLAY_STATE_ENDED) or
                    video.isAfterEndCreditsPosition(watchPosition.toLong()) -> {
                removeVideoFromWatchNext(context, video)
            }

            // Add or update unfinished movie to Watch Next channel.
            hasVideoStarted(video.duration(), watchPosition) -> {
                insertOrUpdateVideoToWatchNext(
                    video,
                    watchPosition,
                    WATCH_NEXT_TYPE_CONTINUE,
                    context
                )
            }
            else -> {
                Timber.w(
                    "Video not started yet. Can't add to WatchNext.watchPosition: %s, duration: %d",
                    watchPosition,
                    video.duration().toMillis()
                )
            }
        }
    }

    /**
     * Handle operations for Watch Next channel for video type 'Episode'.
     */
    internal fun handleWatchNextForEpisode(
        video: Video,
        watchPosition: Int,
        state: String?,
        videoRepository: VideoRepository,
        context: Context
    ) {
        Timber.v("Adding/remove episode to Watch Next. Video Name: ${video.name}")

        var newWatchNextVideo: Video? = null
        when {
            // If episode has finished, remove from Watch Next channel.
            (state == PLAY_STATE_ENDED) or
                    video.isAfterEndCreditsPosition(watchPosition.toLong()) -> {
                removeVideoFromWatchNext(context, video)

                // Add next episode from TV series.
                // TODO: Step 3.1 - Add next episode to Watch Next channel.

                // End of step 3.1ss
            }

            // Add or update unfinished episode to Watch Next channel.
            hasVideoStarted(video.duration(), watchPosition) -> {
                insertOrUpdateVideoToWatchNext(
                    video,
                    watchPosition,
                    WATCH_NEXT_TYPE_CONTINUE,
                    context
                )
                newWatchNextVideo = video
            }
            else -> {
                Timber.w(
                    "Video not started yet. Can't add to WatchNext.watchPosition: %s, duration: %d",
                    watchPosition,
                    video.duration().toMillis()
                )
            }
        }

        /**
         *  We suggest to keep only 1 episode for each TV show in Watch Next, remove previous
         *  watched episodes and only keep the last watched one.
         *  1. Figures out which episode from this TV Series are visible in the Watch Next row;
         *  2. Sorts the filtered episodes and excludes the last watched episode;
         *  3. Removes all other episode from Watch Next row;
         */
        // TODO: Step 4.1 - Keep only 1 episode from each TV show.

        // End of step 4.1
    }
}