package kr.triprecap.app.data

class InMemoryTripStoreTest : TripStoreContract() {
    override fun newStore() = InMemoryTripStore()
}
