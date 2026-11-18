/*
 * Kinetix3D Studio — ui-studio
 * Demo ProjectState used by the timeline and the live renderer until the real
 * project/session layer lands.
 */

package com.kinetix3d.ui

import com.kinetix3d.model.AudioClipPayload
import com.kinetix3d.model.ClipNode
import com.kinetix3d.model.CompSettings
import com.kinetix3d.model.CompositionGraph
import com.kinetix3d.model.NodeGraph
import com.kinetix3d.model.ProjectState
import com.kinetix3d.model.TextClipPayload
import com.kinetix3d.model.TextSource
import com.kinetix3d.model.TrackNode
import com.kinetix3d.model.TrackType
import com.kinetix3d.model.VideoClipPayload
import com.kinetix3d.render.demoClipGraph
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

fun demoProjectState(): ProjectState {
    val settings = CompSettings(width = 3840, height = 2160, fps = 60.0)
    val graph = demoClipGraph()
    val second = 1_000_000L
    val tracks =
        persistentListOf(
            TrackNode("V2", TrackType.VIDEO),
            TrackNode("V1", TrackType.VIDEO),
            TrackNode("A1", TrackType.AUDIO),
        )
    val clips =
        persistentMapOf(
            "Title" to
                ClipNode(
                    clipId = "Title",
                    trackId = "V2",
                    timelineInUs = 2 * second,
                    timelineOutUs = 6 * second,
                    payload = TextClipPayload(TextSource("Title")),
                    nodeGraph = graph,
                ),
            "Logo" to
                ClipNode(
                    clipId = "Logo",
                    trackId = "V2",
                    timelineInUs = 14 * second,
                    timelineOutUs = 19 * second,
                    payload = TextClipPayload(TextSource("Logo")),
                    nodeGraph = graph,
                ),
            "Winter Olympics" to
                ClipNode(
                    clipId = "Winter Olympics",
                    trackId = "V1",
                    timelineInUs = 0L,
                    timelineOutUs = 8 * second,
                    payload = VideoClipPayload,
                    nodeGraph = graph,
                ),
            "B-Roll" to
                ClipNode(
                    clipId = "B-Roll",
                    trackId = "V1",
                    timelineInUs = 8 * second,
                    timelineOutUs = 14 * second,
                    payload = VideoClipPayload,
                    nodeGraph = graph,
                ),
            "Interview" to
                ClipNode(
                    clipId = "Interview",
                    trackId = "V1",
                    timelineInUs = 14 * second,
                    timelineOutUs = 24 * second,
                    payload = VideoClipPayload,
                    nodeGraph = graph,
                ),
            "Music Bed" to
                ClipNode(
                    clipId = "Music Bed",
                    trackId = "A1",
                    timelineInUs = 0L,
                    timelineOutUs = 24 * second,
                    payload = AudioClipPayload,
                    nodeGraph = NodeGraph(),
                ),
        )
    val composition = CompositionGraph("main", "Demo Edit", settings, clips, tracks)
    return ProjectState(
        projectId = "demo",
        settings = settings,
        compositions = persistentMapOf("main" to composition),
        activeCompId = "main",
    )
}
