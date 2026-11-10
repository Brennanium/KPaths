package dev.kpaths.compiler.consumer

import dev.kpaths.compiler.fixture.Person
import dev.kpaths.compiler.fixture.RenamedDelivery
import dev.kpaths.core.CaseKeyPath
import dev.kpaths.compiler.fixture.ConnectionStatus
import dev.kpaths.compiler.fixture.RequestedDocument
import dev.kpaths.compiler.fixture.FixtureBox
import dev.kpaths.compiler.fixture.AdaptedDocument
import dev.kpaths.compiler.fixture.CellTextPath
import dev.kpaths.compiler.fixture.SyntheticOwner
import dev.kpaths.compiler.integration.BinaryPath
import dev.kpaths.core.KeyPath
import dev.kpaths.core.CasePathable

// The consumer module intentionally has no `-Xplugin` argument.
val pathsFromAnotherModule: Person.Paths = Person.Paths
val namePathFromAnotherModule: KeyPath<Person, String> = Person.Paths.name
val renamedCaseFromAnotherModule: CaseKeyPath<RenamedDelivery, String> =
  RenamedDelivery.Paths.MessageCase.text
val casePropertyFromAnotherModule: KeyPath<RenamedDelivery.Message, String> =
  RenamedDelivery.Paths.MessagePaths.text
val boundCaseFromAnotherModule: CaseKeyPath<RenamedDelivery, String> =
  RenamedDelivery.Paths.Tagged.textFor(id = 1)
val enumConformanceFromAnotherModule: CasePathable<ConnectionStatus> = ConnectionStatus.Online

// This catalog was requested by a different compiler plugin in the producer module.
val contributedPathsFromAnotherModule: RequestedDocument.GeneratedPaths = RequestedDocument.GeneratedPaths
val adaptedPathFromAnotherModule: CellTextPath = AdaptedDocument.GeneratedPaths.title
val syntheticPathFromAnotherModule: BinaryPath<SyntheticOwner.Snapshot, List<String?>> = SyntheticOwner.Snapshot.Paths.title
val syntheticGenericPathFromAnotherModule: BinaryPath<SyntheticOwner.GenericSnapshot<String?>, String?> =
  SyntheticOwner.GenericSnapshot.Paths<String?>().title
val genericPathFromAnotherModule = FixtureBox.Paths<String?>().value
