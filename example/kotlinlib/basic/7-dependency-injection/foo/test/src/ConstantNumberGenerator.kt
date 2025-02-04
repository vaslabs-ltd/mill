package io.vaslabs

import javax.inject.Inject

class ConstantNumberGenerator @Inject constructor() : NumberGenerator {
    override fun generate(): Int {
        return 42
    }
}