class MockOptionalProtocol: OptionalProtocol {

    var invokedOptionals = false
    var invokedOptionalsParameters: (optional: Double?, Void)?
    var stubbedOptionalsResult: Int!
    func optionals(optional: Double?) -> Int? {
        invokedOptionals = true
        invokedOptionalsParameters = (optional, ())
        return stubbedOptionalsResult
    }
    var invokedUnwrappedOptionals = false
    var invokedUnwrappedOptionalsParameters: (unwrapped: UInt?, Void)?
    var stubbedUnwrappedOptionalsResult: UInt!
    func unwrappedOptionals(unwrapped: UInt!) -> UInt! {
        invokedUnwrappedOptionals = true
        invokedUnwrappedOptionalsParameters = (unwrapped, ())
        return stubbedUnwrappedOptionalsResult
    }
    var invokedMixed = false
    var invokedMixedParameters: (unwrapped: UInt?, optional: String?, value: Int)?
    var stubbedMixedResult: String!
    func mixed(unwrapped: UInt!, optional: String?, value: Int) -> String {
        invokedMixed = true
        invokedMixedParameters = (unwrapped, optional, value)
        return stubbedMixedResult
    }
}
