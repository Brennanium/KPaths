package dev.kpaths.compiler.fixture

import dev.kpaths.annotations.Pathable
import dev.kpaths.annotations.PathName
import dev.kpaths.annotations.CaseName
import dev.kpaths.core.CaseKeyPath
import dev.kpaths.core.CasePathable
import dev.kpaths.core.KeyPath
import dev.kpaths.core.KeyPathBase
import dev.kpaths.compiler.integration.Cell
import dev.kpaths.core.WritableKeyPath
import dev.kpaths.core.TypeId
import dev.kpaths.core.PartialCaseKeyPath

@Target(AnnotationTarget.CLASS)
annotation class ContributedCatalog

@ContributedCatalog
data class AdaptedDocument(val title: Cell<String>)

class CellTextPath(private val storage: KeyPath<AdaptedDocument, Cell<String>>) :
  KeyPathBase<AdaptedDocument, String>() {
  override val __kpaths_id get() = storage.__kpaths_id
  override val __kpaths_valueType get() = TypeId.of<String>()
  override fun get(root: AdaptedDocument): String = storage.get(root).value
}

fun makeCellTextPath(storage: KeyPath<AdaptedDocument, Cell<String>>): CellTextPath = CellTextPath(storage)
val nativeAdaptedTitle: CellTextPath = AdaptedDocument.GeneratedPaths.title

/** Proves the same synthetic nested declaration reaches Kotlin/Native's backend. */
@Pathable
data class NativePerson(val name: String)

@Pathable
data class NativeBox<T>(var value: T)

val nativeBoxPath: WritableKeyPath<NativeBox<String?>, String?> = NativeBox.Paths<String?>().value
val nativeBoxClassPath: WritableKeyPath<NativeBox<String>, String> = NativeBox.Paths.of(String::class).value
val nativeBoxTokenPath: WritableKeyPath<NativeBox<String?>, String?> =
  NativeBox.Paths.of<String?>(TypeId.of<String?>()).value

@Pathable
sealed interface NativeEvent<out T> {
  data object Idle : NativeEvent<Nothing>
  data class Value<T>(val value: T) : NativeEvent<T>
  data class Tagged<T>(val id: Int, val value: T) : NativeEvent<T>
}

val nativeValueCase: CaseKeyPath<NativeEvent<String>, NativeEvent.Value<String>> =
  NativeEvent.Paths<String>().Value
val nativeValuePayload: CaseKeyPath<NativeEvent<String?>, String?> = NativeEvent.Paths<String?>().Value.value
val nativeValueProperty: KeyPath<NativeEvent.Value<String?>, String?> = NativeEvent.Paths<String?>().ValuePaths.value
val nativeAllCases: List<PartialCaseKeyPath<NativeEvent<String?>>> = NativeEvent.Paths<String?>().allCasePaths
val nativeBoundValue: CaseKeyPath<NativeEvent<String?>, String?> =
  NativeEvent.Paths<String?>().Tagged.valueFor(id = 1)

val nativePersonPaths: NativePerson.Paths = NativePerson.Paths
val nativePersonNamePath: KeyPath<NativePerson, String> = NativePerson.Paths.name

@Pathable
data class NativeRenamedPerson(@PathName("display") var name: String)

val nativeDisplayPath: WritableKeyPath<NativeRenamedPerson, String> = NativeRenamedPerson.Paths.display

/** Proves enum-entry case paths use the same target-neutral generated IR on Native. */
@Pathable
enum class NativeConnectionStatus { Offline, Online }

@Pathable
enum class NativeRenamedStatus { @CaseName("Available") Online }

val nativeAvailablePath: CaseKeyPath<NativeRenamedStatus, Unit> = NativeRenamedStatus.Paths.Available

val nativeOnlinePath: CaseKeyPath<NativeConnectionStatus, Unit> = NativeConnectionStatus.Paths.Online
val nativeCasePathable: CasePathable<NativeConnectionStatus> = NativeConnectionStatus.Online

@Pathable
sealed interface NativeRequest {
  @CaseName("OpenCase")
  data class Open(val id: String, val count: Int?) : NativeRequest
  data class Edit(val id: String, val count: Int, val selected: Boolean) : NativeRequest
}

val nativeOpen: CaseKeyPath<NativeRequest, Pair<String, Int?>> = NativeRequest.Paths.OpenCase.payload
val nativeEdit: CaseKeyPath<NativeRequest, Triple<String, Int, Boolean>> = NativeRequest.Paths.Edit.payload
val nativeOpenId: KeyPath<NativeRequest.Open, String> = NativeRequest.Paths.OpenPaths.id
val nativeBoundCount: CaseKeyPath<NativeRequest, Int?> = NativeRequest.Paths.OpenCase.countFor(id = "screen")
