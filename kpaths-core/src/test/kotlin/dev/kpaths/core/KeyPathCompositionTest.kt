package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class KeyPathCompositionTest {
  @Test
  fun `two writable paths compose into an immutable writable path`() {
    val city = PersonAddressPath.then(AddressCityPath)
    val original = Person(address = Address(city = "Paris"))

    assertEquals("Paris", city.get(original))
    assertEquals(Person(address = Address(city = "Toronto")), city.set(original, "Toronto"))
    assertEquals(
      PathId(
        TypeId("dev.kpaths.core.Person"),
        listOf(PathSegment.Property("address"), PathSegment.Property("city")),
      ),
      city.__kpaths_id,
    )
  }

  @Test
  fun `a read-only final path makes the composition read-only`() {
    val cityLength: KeyPath<Person, Int> = PersonAddressPath.then(CityLengthPath)

    assertEquals(5, cityLength.get(Person(address = Address(city = "Paris"))))
  }

  @Test
  fun `a reference-writable outer path preserves root identity after composition`() {
    val city: ReferenceWritableKeyPath<AddressBox, String> = BoxAddressPath.then(AddressCityPath)
    val box = AddressBox(Address(city = "Paris"))

    val returned = city.set(box, "Toronto")

    assertSame(box, returned)
    assertEquals("Toronto", box.address.city)
  }

  @Test
  fun `key paths can be passed directly where Kotlin expects a function`() {
    val city = PersonAddressPath.then(AddressCityPath)
    val people = listOf(
      Person(Address("Paris")),
      Person(Address("Toronto")),
    )

    assertEquals(listOf("Paris", "Toronto"), people.map(city))
    assertEquals(people.reversed(), people.sortedByDescending(city))
  }

  @Test
  fun `nullable key paths optionally compose as a read-only route`() {
    val address = keyPath(OptionalPerson::address)
    val city = keyPath(OptionalAddress::city)
    val optionalCity: KeyPath<OptionalPerson, String?> = address.thenOptional(city)

    assertEquals("Paris", optionalCity.get(OptionalPerson(OptionalAddress("Paris"))))
    assertEquals(null, optionalCity.get(OptionalPerson(null)))
    assertEquals(
      PathId(
        TypeId.of<OptionalPerson>(),
        listOf(
          PathSegment.Property("address"),
          PathSegment.OptionalChain,
          PathSegment.Property("city"),
        ),
      ),
      optionalCity.__kpaths_id,
    )
    assertEquals(TypeId.of<String?>(), optionalCity.__kpaths_valueType)
  }

  @Test
  fun `force-unwrapped paths retain writable replacement and fail for an absent intermediate`() {
    val address = writableKeyPath(
      get = OptionalPerson::address,
      set = { person, value -> person.copy(address = value) },
    )
    val city = writableKeyPath(
      get = OptionalAddress::city,
      set = { address, value -> address.copy(city = value) },
    )
    val forcedCity: WritableKeyPath<OptionalPerson, String> = address.thenForceUnwrapped(city)

    assertEquals("Paris", forcedCity.get(OptionalPerson(OptionalAddress("Paris"))))
    assertEquals(
      OptionalPerson(OptionalAddress("Toronto")),
      forcedCity.set(OptionalPerson(OptionalAddress("Paris")), "Toronto"),
    )
    assertFailsWith<NullPointerException> {
      forcedCity.get(OptionalPerson(null))
    }
    assertFailsWith<NullPointerException> {
      forcedCity.set(OptionalPerson(null), "Toronto")
    }
    assertEquals(
      PathId(
        TypeId.of<OptionalPerson>(),
        listOf(
          PathSegment.Property("address"),
          PathSegment.ForceUnwrap,
          PathSegment.Property("city"),
        ),
      ),
      forcedCity.__kpaths_id,
    )
  }

  @Test
  fun `force-unwrapped composition preserves reference-writable roots`() {
    val city = writableKeyPath(
      get = OptionalAddress::city,
      set = { address, value -> address.copy(city = value) },
    )
    val forcedCity: ReferenceWritableKeyPath<OptionalAddressBox, String> =
      BoxOptionalAddressPath.thenForceUnwrapped(city)
    val box = OptionalAddressBox(OptionalAddress("Paris"))

    assertSame(box, forcedCity.set(box, "Toronto"))
    assertEquals("Toronto", box.address?.city)
    assertFailsWith<NullPointerException> {
      forcedCity.set(OptionalAddressBox(null), "Ottawa")
    }
  }
}

private data class Person(val address: Address)

private data class Address(val city: String)

private data class OptionalPerson(val address: OptionalAddress?)

private data class OptionalAddress(val city: String)

private class AddressBox(var address: Address)

private class OptionalAddressBox(var address: OptionalAddress?)

private object PersonAddressPath : WritableKeyPathBase<Person, Address>() {
  override val __kpaths_id: PathId = PathId.property(TypeId("dev.kpaths.core.Person"), "address")
  override val __kpaths_valueType: TypeId = TypeId("dev.kpaths.core.Address")

  override fun get(root: Person): Address = root.address

  override fun set(root: Person, value: Address): Person = root.copy(address = value)
}

private object AddressCityPath : WritableKeyPathBase<Address, String>() {
  override val __kpaths_id: PathId = PathId.property(TypeId("dev.kpaths.core.Address"), "city")
  override val __kpaths_valueType: TypeId = TypeId.of<String>()

  override fun get(root: Address): String = root.city

  override fun set(root: Address, value: String): Address = root.copy(city = value)
}

private object CityLengthPath : KeyPathBase<Address, Int>() {
  override val __kpaths_id: PathId = PathId.property(TypeId("dev.kpaths.core.Address"), "cityLength")
  override val __kpaths_valueType: TypeId = TypeId.of<Int>()

  override fun get(root: Address): Int = root.city.length
}

private object BoxAddressPath : ReferenceWritableKeyPathBase<AddressBox, Address>() {
  override val __kpaths_id: PathId = PathId.property(TypeId("dev.kpaths.core.AddressBox"), "address")
  override val __kpaths_valueType: TypeId = TypeId("dev.kpaths.core.Address")

  override fun get(root: AddressBox): Address = root.address

  override fun setInPlace(root: AddressBox, value: Address) {
    root.address = value
  }
}

private object BoxOptionalAddressPath : ReferenceWritableKeyPathBase<OptionalAddressBox, OptionalAddress?>() {
  override val __kpaths_id: PathId = PathId.property(
    TypeId("dev.kpaths.core.OptionalAddressBox"),
    "address",
  )
  override val __kpaths_valueType: TypeId = TypeId.of<OptionalAddress?>()

  override fun get(root: OptionalAddressBox): OptionalAddress? = root.address

  override fun setInPlace(root: OptionalAddressBox, value: OptionalAddress?) {
    root.address = value
  }
}
