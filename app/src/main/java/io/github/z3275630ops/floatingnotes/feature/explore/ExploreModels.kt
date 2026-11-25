package io.github.z3275630ops.floatingnotes.feature.explore

import io.github.z3275630ops.floatingnotes.data.discovery.ActiveWalkSnapshot
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapMode
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapSnapshot
import io.github.z3275630ops.floatingnotes.data.discovery.DailyReviewSnapshot
import io.github.z3275630ops.floatingnotes.data.discovery.DualCollisionBridge
import io.github.z3275630ops.floatingnotes.data.discovery.DualCollisionPair
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceBasketItem
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceHit
import io.github.z3275630ops.floatingnotes.data.discovery.FindSearchResult
import io.github.z3275630ops.floatingnotes.data.discovery.RelatedNoteMatch
import io.github.z3275630ops.floatingnotes.data.discovery.RetrievalCoverage
import io.github.z3275630ops.floatingnotes.data.discovery.TagRecommendation
import io.github.z3275630ops.floatingnotes.data.discovery.WalkPostcardSummary
import io.github.z3275630ops.floatingnotes.data.local.NoteScope

internal enum class ExploreSection(val label: String) {
    Review("回顾"),
    Find("找一找"),
    Walk("漫步"),
}

internal enum class WalkMode(val label: String) {
    Single("单卡漫步"),
    Dual("双卡对撞"),
}

internal data class ExploreUiState(
    val loading: Boolean = false,
    val indexing: Boolean = false,
    val review: DailyReviewSnapshot? = null,
    val coverage: RetrievalCoverage? = null,
    val findQuery: String = "",
    val findScope: NoteScope = NoteScope(),
    val findResult: FindSearchResult? = null,
    val evidenceBasket: List<EvidenceBasketItem> = emptyList(),
    val walk: ActiveWalkSnapshot? = null,
    val walkPostcard: WalkPostcardSummary? = null,
    val walkPrivacyHidden: Boolean = false,
    val walkMode: WalkMode = WalkMode.Single,
    val dualPair: DualCollisionPair? = null,
    val dualHistory: List<DualCollisionPair> = emptyList(),
    val dualBridge: DualCollisionBridge? = null,
    val dualBridgeFarFetched: Boolean = false,
    val dualInsufficientCount: Int? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

internal data class EditorDiscoveryState(
    val noteId: String? = null,
    val loadingRelated: Boolean = false,
    val relatedNotes: List<RelatedNoteMatch> = emptyList(),
    val edgeEcho: EvidenceHit? = null,
    val sessionMuted: Boolean = false,
    val globalEchoEnabled: Boolean = true,
    val lastAutoShownAtMs: Long = 0L,
    val dismissedNoteIds: Set<String> = emptySet(),
    val recommendedTags: List<TagRecommendation> = emptyList(),
)

internal data class CognitiveMapUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val mode: CognitiveMapMode = CognitiveMapMode.TOPIC_MAP,
    val activeMonthFilter: String? = null,
    val reclusterForMonth: Boolean = false,
    val customClusterNames: Map<String, String> = emptyMap(),
    val snapshot: CognitiveMapSnapshot? = null,
    val errorMessage: String? = null,
)
