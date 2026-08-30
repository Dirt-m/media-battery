package site.dirt23.battery.core.model

// An hour rule holds between two times of day on the local wall clock, wrapping midnight,
// and does one thing: block sites, stop counting use on them, slow or stop charging, or
// lower the capacity. Rules sync per key with the other settings, so the wire shape is
// fixed: {id, from:"HH:MM", to:"HH:MM", action, scope, sites, percent, minutes}. Field
// names here match it so serialization needs no renaming.
//
// Two types: RawHourRule is whatever arrived (storage, a sync pull, the editor) with every
// field optional; HourRule is what came out of Rules.sanitizeRules, the only way to build
// one from untrusted input.

/** The four things a rule can do. The wire value is the JSON string. */
enum class RuleAction(val wire: String) {
    BLOCK("block"),
    OFF("off"),
    RECHARGE("recharge"),
    CAPACITY("capacity");

    companion object {
        fun fromWire(s: String?): RuleAction? = entries.firstOrNull { it.wire == s }
    }
}

/** Which sites a block or off rule covers. Recharge and capacity rules carry no scope. */
enum class RuleScope(val wire: String) {
    ALL("all"),
    ONLY("only"),
    EXCEPT("except");

    companion object {
        fun fromWire(s: String?): RuleScope? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * A validated rule. `from` and `to` are stored verbatim, not normalized: "9:00" parses the
 * same as "09:00" and round trips unchanged, as in the extension. `scope`/`sites` mean
 * something only on a block or off rule, `percent` only on recharge, `minutes` only on
 * capacity; the defaults are what the extension leaves them as otherwise.
 */
data class HourRule(
    val id: String,
    val from: String,
    val to: String,
    val action: RuleAction,
    val scope: RuleScope = RuleScope.ALL,
    val sites: List<String> = emptyList(),
    val percent: Int = 0,
    val minutes: Int = 0,
)

/**
 * A rule as it arrives, before validation. Everything is optional because everything is
 * untrusted. `percent` and `minutes` are Double so a non finite value survives the trip to
 * the validator, which treats the two differently (see Rules.sanitizeRules).
 *
 * One JS shape is not representable: `minutes: null`, which JS coerces to 0 and clamps to 1
 * rather than dropping. Null here means absent, the dropping case. Nothing the extension
 * writes produces an explicit null there.
 */
data class RawHourRule(
    val id: String? = null,
    val from: String? = null,
    val to: String? = null,
    val action: String? = null,
    val scope: String? = null,
    val sites: List<String?>? = null,
    val percent: Double? = null,
    val minutes: Double? = null,
)
