package dev.kpaths.compiler.fixture

import dev.kpaths.annotations.Pathable
import dev.kpaths.annotations.PathName
import dev.kpaths.annotations.CaseName
import dev.kpaths.core.CaseKeyPath
import dev.kpaths.core.CasePathable
import dev.kpaths.core.KeyPath
import dev.kpaths.core.WritableKeyPath

/** Proves the same synthetic nested declaration reaches Kotlin/Native's backend. */
@Pathable
data class NativePerson(val name: String)

@Pathable
data class NativeBox<T>(var value: T)

val nativeBoxPath: WritableKeyPath<NativeBox<String?>, String?> = NativeBox.Paths<String?>().value

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
