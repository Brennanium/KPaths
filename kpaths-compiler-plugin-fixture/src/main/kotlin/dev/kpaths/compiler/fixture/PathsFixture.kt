package dev.kpaths.compiler.fixture

import dev.kpaths.annotations.KPathsAdapter
import dev.kpaths.annotations.KPathsAdapterFactory
import dev.kpaths.annotations.KPathsPathable
import dev.kpaths.annotations.Pathable
import dev.kpaths.annotations.CaseName
import dev.kpaths.core.KeyPath
import dev.kpaths.core.CaseKeyPath
import dev.kpaths.core.CasePathable
import dev.kpaths.core.PartialCaseKeyPath
import dev.kpaths.core.ReferenceWritableKeyPath
import dev.kpaths.core.WritableKeyPath
import dev.kpaths.compiler.integration.Cell as LibraryCell
import dev.kpaths.compiler.integration.LibraryPathable

@Pathable
data class Person(val name: String)

/** This must resolve without a user-declared companion and without a KSP-generated source file. */
val personPaths: Person.Paths = Person.Paths
val personNamePath: KeyPath<Person, String> = Person.Paths.name

@Pathable
sealed interface Delivery {
  data object Refresh : Delivery
  data class Message(val text: String) : Delivery
}

val deliveryRefreshPath: CaseKeyPath<Delivery, Delivery.Refresh> = Delivery.Paths.Refresh
val deliveryMessagePath: CaseKeyPath<Delivery, Delivery.Message> = Delivery.Paths.Message
val deliveryMessageTextPath: CaseKeyPath<Delivery, String> = Delivery.Paths.Message.text
val deliveryAllCasePaths: List<PartialCaseKeyPath<Delivery>> = Delivery.Paths.allCasePaths
val deliveryCasePathable: CasePathable<Delivery> = Delivery.Message("query")
val deliveryActiveCase: PartialCaseKeyPath<Delivery> = Delivery.Message("query").case

@Pathable
sealed interface RenamedDelivery {
  @CaseName("MessageCase") data class Message(val text: String) : RenamedDelivery
  data class Tagged(val id: Int, val text: String) : RenamedDelivery
}

@Pathable
enum class ConnectionStatus {
  Offline,
  Connecting,
  Online,
}

val onlineStatusPath: CaseKeyPath<ConnectionStatus, Unit> = ConnectionStatus.Paths.Online
val connectionStatusAllCasePaths: List<PartialCaseKeyPath<ConnectionStatus>> =
  ConnectionStatus.Paths.allCasePaths
val connectionStatusActiveCase: PartialCaseKeyPath<ConnectionStatus> = ConnectionStatus.Online.case
val automaticEnumCasePathable: CasePathable<ConnectionStatus> = ConnectionStatus.Online

/** Enum roots expose `.case`; source conformance provides the generic marker interface. */
@Pathable
enum class ManuallyCasePathableConnectionStatus : CasePathable<ManuallyCasePathableConnectionStatus> {
  Offline,
  Online,
}

val manuallyCasePathableConnectionStatus: CasePathable<ManuallyCasePathableConnectionStatus> =
  ManuallyCasePathableConnectionStatus.Online

@Pathable
class Draft(var text: String)

val draftTextPath: ReferenceWritableKeyPath<Draft, String> = Draft.Paths.text

@Pathable
data class DraftSnapshot(var text: String, val revision: Int)

val draftSnapshotTextPath: WritableKeyPath<DraftSnapshot, String> = DraftSnapshot.Paths.text

@Pathable
data class ExtendedDraftSnapshot(val text: String) {
  var isPinned: Boolean = false
}

val extendedDraftSnapshotPinnedPath: WritableKeyPath<ExtendedDraftSnapshot, Boolean> =
  ExtendedDraftSnapshot.Paths.isPinned

@Pathable(copyWritableVals = true)
data class CopyWritableSnapshot(val text: String, val revision: Int)

val copyWritableSnapshotTextPath: WritableKeyPath<CopyWritableSnapshot, String> =
  CopyWritableSnapshot.Paths.text

@Pathable
data class OptionalDocument(val title: String?)

val optionalDocumentTitlePath: KeyPath<OptionalDocument, String?> = OptionalDocument.Paths.title

/** A library-owned opt-in annotation, deliberately not named after any prospective integration. */
@KPathsPathable(copyWritableVals = true)
@Target(AnnotationTarget.CLASS)
annotation class Catalogued

/** A generic storage type carrying a binary-visible specialization declaration. */
@KPathsAdapter(factory = CellPaths::class)
class Cell<Value>(var value: Value)

/** Placeholder for the eventual runtime factory ABI; this fixture proves annotation discovery. */
object CellPaths : KPathsAdapterFactory

@Catalogued
data class Document(val title: Cell<String>)

/** This must resolve through @Catalogued rather than a directly applied @Pathable annotation. */
val documentPaths: Document.Paths = Document.Paths
val documentTitlePath: WritableKeyPath<Document, Cell<String>> = Document.Paths.title

@Pathable
data class ProjectedDocument(
  val nullableTitle: Cell<String?>,
  val names: MutableList<out String>,
  val unknown: List<*>,
)

val projectedDocumentNullableTitlePath: KeyPath<ProjectedDocument, Cell<String?>> =
  ProjectedDocument.Paths.nullableTitle
val projectedDocumentNamesPath: KeyPath<ProjectedDocument, MutableList<out String>> =
  ProjectedDocument.Paths.names
val projectedDocumentUnknownPath: KeyPath<ProjectedDocument, List<*>> =
  ProjectedDocument.Paths.unknown

/** This annotation and wrapper type both come from another compiled module. */
@LibraryPathable
data class ImportedDocument(val title: LibraryCell<String>)

val importedDocumentPaths: ImportedDocument.Paths = ImportedDocument.Paths
val importedDocumentTitlePath: KeyPath<ImportedDocument, LibraryCell<String>> = ImportedDocument.Paths.title

@Target(AnnotationTarget.CLASS)
annotation class ContributedCatalog

/** Its catalog is requested by a separate compiler plugin through `session.kpaths.pathable`. */
@ContributedCatalog
data class RequestedDocument(val title: String, val identifier: String = "id", var revision: Int = 0)

val requestedDocumentPaths: RequestedDocument.GeneratedPaths = RequestedDocument.GeneratedPaths
val requestedDocumentTitlePath: WritableKeyPath<RequestedDocument, String> =
  RequestedDocument.GeneratedPaths.title
val requestedDocumentIdentifierPath: KeyPath<RequestedDocument, String> =
  RequestedDocument.GeneratedPaths.identifier
val requestedDocumentRevisionPath: WritableKeyPath<RequestedDocument, Int> =
  RequestedDocument.GeneratedPaths.revision

@ContributedCatalog
sealed interface RequestedEvent {
  data class Protected(val value: String) : RequestedEvent
  data class Editable(val value: String) : RequestedEvent
}

val protectedValuePath: KeyPath<RequestedEvent.Protected, String> =
  RequestedEvent.GeneratedPaths.ProtectedPaths.value
val editableValuePath: WritableKeyPath<RequestedEvent.Editable, String> =
  RequestedEvent.GeneratedPaths.EditablePaths.value
