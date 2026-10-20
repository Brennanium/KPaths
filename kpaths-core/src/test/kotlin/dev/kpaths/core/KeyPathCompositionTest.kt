package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
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
      city.id,
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
}

private data class Person(val address: Address)

private data class Address(val city: String)

private class AddressBox(var address: Address)

private object PersonAddressPath : WritableKeyPathBase<Person, Address>() {
  override val id: PathId = PathId.property(TypeId("dev.kpaths.core.Person"), "address")
  override val valueType: TypeId = TypeId("dev.kpaths.core.Address")

  override fun get(root: Person): Address = root.address

  override fun set(root: Person, value: Address): Person = root.copy(address = value)
}

private object AddressCityPath : WritableKeyPathBase<Address, String>() {
  override val id: PathId = PathId.property(TypeId("dev.kpaths.core.Address"), "city")
  override val valueType: TypeId = TypeId.string

  override fun get(root: Address): String = root.city

  override fun set(root: Address, value: String): Address = root.copy(city = value)
}

private object CityLengthPath : KeyPathBase<Address, Int>() {
  override val id: PathId = PathId.property(TypeId("dev.kpaths.core.Address"), "cityLength")
  override val valueType: TypeId = TypeId.int

  override fun get(root: Address): Int = root.city.length
}

private object BoxAddressPath : ReferenceWritableKeyPathBase<AddressBox, Address>() {
  override val id: PathId = PathId.property(TypeId("dev.kpaths.core.AddressBox"), "address")
  override val valueType: TypeId = TypeId("dev.kpaths.core.Address")

  override fun get(root: AddressBox): Address = root.address

  override fun setInPlace(root: AddressBox, value: Address) {
    root.address = value
  }
}
