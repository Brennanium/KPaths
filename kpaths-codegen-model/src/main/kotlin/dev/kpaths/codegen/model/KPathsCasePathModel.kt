package dev.kpaths.codegen.model

/** Default namespace for a sealed case's property KeyPaths. */
public fun kPathsCasePropertyCatalogName(sourceCaseName: String): String = sourceCaseName + "Paths"

/** One named factory that focuses a payload property while binding the remaining properties. */
public data class KPathsBoundPayloadMember(
  public val targetIndex: Int,
  public val targetName: String,
  public val boundNames: List<String>,
) {
  public val generatedName: String get() = targetName + "For"
}

/** Plans bound payload factories in primary-constructor order. */
public fun planKPathsBoundPayloadMembers(propertyNames: List<String>): List<KPathsBoundPayloadMember> =
  if (propertyNames.size !in 2..3) emptyList() else propertyNames.mapIndexed { index, name ->
    KPathsBoundPayloadMember(index, name, propertyNames.filterIndexed { otherIndex, _ -> otherIndex != index })
  }

/**
 * Renderer-neutral description of one generated CaseKeyPath and its canonical associated value.
 *
 * Frontends discover their own source symbols, validate them with [KPathsCaseShape], and then
 * create this model from the case name and the payload form they can emit. The model deliberately
 * contains no KSP, FIR, or IR types so source generation and compiler-plugin generation make the
 * same `allCasePaths` decision.
 */
public data class KPathsCasePathModel(
  /** The declared sealed-case or enum-entry name used in structural path identity. */
  public val sourceName: String,
  /** The generated public member name for the whole-case path. */
  public val generatedName: String,
  public val kind: KPathsCaseKind,
  /** The canonical associated value when this backend can emit one. */
  public val payload: KPathsCasePayload? = null,
) {
  init {
    require(sourceName.isNotBlank()) { "A KPaths case name cannot be blank." }
    require(generatedName.isNotBlank()) { "A generated KPaths case name cannot be blank." }
    require(kind == KPathsCaseKind.SEALED_VARIANT || payload == KPathsCasePayload.Unit) {
      "An enum entry always carries Unit as its associated value."
    }
  }

  /**
   * The generated member path included in `allCasePaths`, or `null` when no lossless canonical
   * payload path is available. A sealed whole-case path is intentionally not substituted here:
   * Swift's `PartialCaseKeyPath` erases the associated value, not the concrete case object.
   */
  public fun catalogAccess(): KPathsCaseCatalogAccess? = when (kind) {
    KPathsCaseKind.ENUM_ENTRY -> KPathsCaseCatalogAccess.WholeCase
    KPathsCaseKind.SEALED_VARIANT -> payload?.let(KPathsCaseCatalogAccess::Payload)
  }
}

/** The associated-value shape represented by a generated CaseKeyPath. */
public sealed interface KPathsCasePayload {
  /** A case with no associated values, represented by Kotlin's [Unit]. */
  public data object Unit : KPathsCasePayload

  /** A case with one associated property. */
  public data class Single(public val propertyName: String) : KPathsCasePayload {
    init {
      require(propertyName.isNotBlank()) { "A KPaths payload property name cannot be blank." }
    }
  }

  /** A case with two associated properties, represented by Kotlin's [Pair]. */
  public data class Pair(
    public val firstPropertyName: String,
    public val secondPropertyName: String,
  ) : KPathsCasePayload {
    init {
      require(firstPropertyName.isNotBlank() && secondPropertyName.isNotBlank()) {
        "KPaths pair payload property names cannot be blank."
      }
    }
  }

  /** A case with three associated properties, represented by Kotlin's [Triple]. */
  public data class Triple(
    public val firstPropertyName: String,
    public val secondPropertyName: String,
    public val thirdPropertyName: String,
  ) : KPathsCasePayload {
    init {
      require(
        firstPropertyName.isNotBlank() &&
          secondPropertyName.isNotBlank() &&
          thirdPropertyName.isNotBlank(),
      ) { "KPaths triple payload property names cannot be blank." }
    }
  }

  /** The public nested member that exposes this payload CaseKeyPath. */
  public val generatedMemberName: String
    get() = when (this) {
      Unit -> "unit"
      is Single -> propertyName
      is Pair, is Triple -> "payload"
    }

  public companion object {
    /** Creates KPaths' supported payload shape for one to three constructor property names. */
    public fun fromProperties(propertyNames: List<String>): KPathsCasePayload? = when (propertyNames.size) {
      1 -> Single(propertyNames.single())
      2 -> Pair(propertyNames[0], propertyNames[1])
      3 -> Triple(propertyNames[0], propertyNames[1], propertyNames[2])
      else -> null
    }
  }
}

/** A backend-neutral reference to the path that belongs in `allCasePaths`. */
public sealed interface KPathsCaseCatalogAccess {
  /** The whole-case path is itself a Unit payload path, as for an enum entry. */
  public data object WholeCase : KPathsCaseCatalogAccess

  /** A nested payload path on a sealed case's generated whole-case member. */
  public data class Payload(public val payload: KPathsCasePayload) : KPathsCaseCatalogAccess
}

/** One canonical payload path included in a generated case catalog. */
public data class KPathsCaseCatalogEntry(
  public val case: KPathsCasePathModel,
  public val access: KPathsCaseCatalogAccess,
)

/**
 * The cases a backend can include in a catalog and those it cannot represent yet.
 *
 * This records completeness without prescribing an error or substituting a whole-case path
 * for an unsupported payload. Frontends can use their own source locations when reporting it.
 */
public data class KPathsCaseCatalogPlan(
  public val entries: List<KPathsCaseCatalogEntry>,
  public val unavailableCases: List<KPathsCasePathModel>,
) {
  public val isComplete: Boolean get() = unavailableCases.isEmpty()
}

/** Plans catalog contents in declaration order, using only canonical associated-value paths. */
public fun planKPathsCaseCatalog(cases: List<KPathsCasePathModel>): KPathsCaseCatalogPlan {
  val entries = mutableListOf<KPathsCaseCatalogEntry>()
  val unavailable = mutableListOf<KPathsCasePathModel>()
  cases.forEach { case ->
    val access = case.catalogAccess()
    if (access == null) unavailable += case else entries += KPathsCaseCatalogEntry(case, access)
  }
  return KPathsCaseCatalogPlan(entries, unavailable)
}
