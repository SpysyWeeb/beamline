package io.github.spysyweeb.beamline.app.online

/**
 * A type of place a user can add (batch O3, docs/online-design.md 4.3): a category of the search
 * ([code] and [label] as in packbuilder/search/categories.json, [group] its group), the OSM [tags] a
 * new point of it gets (each satisfies one of that category's match rules, so the next pack files it
 * under the same category: PlaceTypesTest checks this against categories.json), whether it usually has
 * a [named] name, and words besides its label that find it in the search before the pack has it ([terms]).
 */
data class AddableType(
    val code: String,
    val label: String,
    val group: String,
    val tags: Map<String, String>,
    val named: Boolean = true,
    val terms: List<String> = emptyList(),
)

/**
 * The list of types "Add a missing place" offers (batch O3). Built from the search's categories:
 * the businesses and public places a driver comes across and can put a point on. Left out, with the
 * reason:
 *  - the umbrellas "Food" and "Lodging" (not a type of their own: the restaurant, the motel are);
 *  - categories the search defines by a name, a brand or a detail rather than a type ("Truck stop",
 *    "Welcome center", the PA state store, "Urgent care", "Emergency room", "Driver license center",
 *    "Oil change", "Vehicle inspection", "Diesel", "State game lands", "State park");
 *  - places OSM maps as areas, not points (rest area, service plaza, every kind of parking, park,
 *    golf course, beach, cemetery, school, college, hospital, shopping mall, campground, RV park,
 *    airport): the design adds only a point (4.3, "Only a point");
 *  - stations (train, subway, bus): OSM's public transport scheme needs more than one point.
 */
object PlaceTypes {
    private fun t(code: String, label: String, group: String, vararg tags: Pair<String, String>, named: Boolean = true, terms: List<String> = emptyList()) =
        AddableType(code, label, group, linkedMapOf(*tags), named, terms)

    private val FOOD = listOf("food", "eat")

    val ADDABLE: List<AddableType> = listOf(
        // on the road
        t("toilets", "Restroom", "road", "amenity" to "toilets", named = false, terms = listOf("toilets", "bathroom")),
        t("rv_dump", "RV dump station", "road", "amenity" to "sanitary_dump_station", named = false),
        t("viewpoint", "Scenic viewpoint", "road", "tourism" to "viewpoint", named = false),
        t("picnic_site", "Picnic area", "road", "tourism" to "picnic_site", named = false),
        // for the car
        t("fuel", "Gas station", "car", "amenity" to "fuel", terms = listOf("gas", "fuel")),
        t("ev_charging", "EV charging", "car", "amenity" to "charging_station", named = false, terms = listOf("charging")),
        t("propane", "Propane", "car", "shop" to "gas"),
        t("car_wash", "Car wash", "car", "amenity" to "car_wash"),
        t("car_repair", "Auto repair", "car", "shop" to "car_repair", terms = listOf("mechanic", "garage")),
        t("tire_shop", "Tire shop", "car", "shop" to "tyres", terms = listOf("tires")),
        t("auto_parts", "Auto parts", "car", "shop" to "car_parts"),
        t("car_dealer", "Car dealer", "car", "shop" to "car"),
        t("car_rental", "Car rental", "car", "amenity" to "car_rental"),
        // food and drink
        t("restaurant", "Restaurant", "food", "amenity" to "restaurant", terms = FOOD),
        t("fast_food", "Fast food", "food", "amenity" to "fast_food", terms = FOOD),
        t("coffee", "Coffee", "food", "amenity" to "cafe", terms = FOOD + listOf("cafe")),
        t("pizza", "Pizza", "food", "amenity" to "restaurant", "cuisine" to "pizza", terms = FOOD),
        t("burger", "Burgers", "food", "amenity" to "fast_food", "cuisine" to "burger", terms = FOOD),
        t("chicken", "Chicken", "food", "amenity" to "fast_food", "cuisine" to "chicken", terms = FOOD),
        t("sandwich", "Sandwiches and hoagies", "food", "amenity" to "fast_food", "cuisine" to "sandwich", terms = FOOD + listOf("subs", "deli")),
        t("chinese", "Chinese", "food", "amenity" to "restaurant", "cuisine" to "chinese", terms = FOOD),
        t("mexican", "Mexican", "food", "amenity" to "restaurant", "cuisine" to "mexican", terms = FOOD),
        t("italian", "Italian", "food", "amenity" to "restaurant", "cuisine" to "italian", terms = FOOD),
        t("asian", "Japanese and sushi", "food", "amenity" to "restaurant", "cuisine" to "japanese", terms = FOOD),
        t("seafood", "Seafood", "food", "amenity" to "restaurant", "cuisine" to "seafood", terms = FOOD),
        t("bbq", "Barbecue and steak", "food", "amenity" to "restaurant", "cuisine" to "barbecue", terms = FOOD),
        t("breakfast", "Breakfast and diners", "food", "amenity" to "restaurant", "cuisine" to "breakfast", terms = FOOD + listOf("diner")),
        t("donut", "Donut shop", "food", "amenity" to "fast_food", "cuisine" to "donut", terms = FOOD + listOf("doughnut")),
        t("ice_cream", "Ice cream", "food", "amenity" to "ice_cream", terms = FOOD),
        t("bakery", "Bakery", "food", "shop" to "bakery", terms = FOOD),
        t("bar", "Bar or pub", "food", "amenity" to "bar", terms = listOf("pub")),
        t("brewery", "Brewery", "food", "craft" to "brewery"),
        t("winery", "Winery", "food", "craft" to "winery"),
        // shops
        t("grocery", "Grocery store", "shop", "shop" to "supermarket", terms = listOf("supermarket", "groceries")),
        t("convenience", "Convenience store", "shop", "shop" to "convenience"),
        t("liquor", "Liquor store", "shop", "shop" to "alcohol"),
        t("beer_distributor", "Beer distributor", "shop", "shop" to "beverages", terms = listOf("beer")),
        t("pharmacy", "Pharmacy", "shop", "amenity" to "pharmacy", "healthcare" to "pharmacy", terms = listOf("drugstore")),
        t("department_store", "Department store", "shop", "shop" to "department_store"),
        t("warehouse_club", "Warehouse club", "shop", "shop" to "wholesale"),
        t("dollar_store", "Dollar store", "shop", "shop" to "variety_store"),
        t("hardware", "Hardware store", "shop", "shop" to "hardware"),
        t("clothes", "Clothing store", "shop", "shop" to "clothes"),
        t("electronics", "Electronics store", "shop", "shop" to "electronics"),
        t("sporting_goods", "Sporting goods and outdoors", "shop", "shop" to "sports"),
        t("pet_store", "Pet store", "shop", "shop" to "pet"),
        t("garden_center", "Garden center", "shop", "shop" to "garden_centre"),
        t("farm_market", "Farm market", "shop", "shop" to "farm"),
        t("laundry", "Laundromat", "shop", "shop" to "laundry"),
        // money, health, lodging
        t("atm", "ATM", "money", "amenity" to "atm", named = false),
        t("bank", "Bank", "money", "amenity" to "bank"),
        t("clinic", "Doctor or clinic", "health", "amenity" to "clinic", "healthcare" to "clinic", terms = listOf("doctor")),
        t("dentist", "Dentist", "health", "amenity" to "dentist", "healthcare" to "dentist"),
        t("veterinary", "Veterinarian", "health", "amenity" to "veterinary", terms = listOf("vet")),
        t("hotel", "Hotel", "lodging", "tourism" to "hotel", terms = listOf("lodging")),
        t("motel", "Motel", "lodging", "tourism" to "motel", terms = listOf("lodging")),
        // public services
        t("post_office", "Post office", "services", "amenity" to "post_office"),
        t("police", "Police", "services", "amenity" to "police"),
        t("fire_station", "Fire station", "services", "amenity" to "fire_station"),
        t("town_hall", "Municipal building", "services", "amenity" to "townhall", terms = listOf("town hall")),
        t("courthouse", "Courthouse", "services", "amenity" to "courthouse"),
        t("library", "Library", "services", "amenity" to "library"),
        // outdoors, things to do, community
        t("waterfall", "Waterfall", "outdoors", "natural" to "waterfall"),
        t("cave", "Cave", "outdoors", "natural" to "cave_entrance"),
        t("peak", "Peak", "outdoors", "natural" to "peak"),
        t("trailhead", "Trailhead", "outdoors", "highway" to "trailhead", terms = listOf("trail")),
        t("boat_launch", "Boat launch", "outdoors", "leisure" to "slipway", named = false),
        t("museum", "Museum", "fun", "tourism" to "museum"),
        t("attraction", "Attraction", "fun", "tourism" to "attraction"),
        t("cinema", "Movie theater", "fun", "amenity" to "cinema", terms = listOf("movies", "cinema")),
        t("gym", "Gym", "fun", "leisure" to "fitness_centre", terms = listOf("fitness")),
        t("place_of_worship", "Place of worship", "community", "amenity" to "place_of_worship", terms = listOf("church")),
    )

    private val byCode = ADDABLE.associateBy { it.code }

    fun byCode(code: String?): AddableType? = code?.let { byCode[it] }

    /**
     * The type of an OSM object's [tags], or null: the addable type whose tags it has, the one with
     * the most tags first (a pizza place is "Pizza", not "Restaurant").
     */
    fun of(tags: Map<String, String>): AddableType? =
        ADDABLE.filter { a -> a.tags.all { (k, v) -> tags[k] == v || (k == "cuisine" && tags[k]?.split(';')?.contains(v) == true) } }
            .maxByOrNull { it.tags.size }

    /** A short word for an object that is not one of [ADDABLE]: its main tag's value in words ("shop=hairdresser": "Hairdresser"). */
    fun words(tags: Map<String, String>): String? {
        of(tags)?.let { return it.label }
        for (k in PlaceEdits.MAIN_KEYS) {
            val v = tags[k] ?: continue
            return v.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        for (k in PlaceEdits.MAIN_KEYS) {
            val v = tags["disused:$k"] ?: continue
            return "Closed " + v.replace('_', ' ')
        }
        return null
    }
}

/**
 * "This is now a different business" and "My place replaced it" (batch O3, docs/next-round.md item 25),
 * as OSM usually does it: the existing object is changed, not a second one added. The old business's
 * own tags go ([goes]); what describes the place rather than the business stays: the address (`addr:*`),
 * the building (`building*`), `level`, `entrance`, `wheelchair`, notes for mappers (`note`, `fixme`),
 * `source`, heights and the like. The list follows the OSM wiki's lifecycle pages (Key:disused:,
 * Lifecycle_prefix: the type keys and their prefixed forms) and the keys of a business in the wiki
 * (Key:brand, Key:operator, Key:cuisine, Key:opening_hours, Key:contact, Key:payment, Key:diet ...).
 * An object that is only an address (no business tags: [isBareAddress]) gets the business tags added
 * and loses nothing.
 */
object BusinessTags {
    /** Keys of the old business that go. */
    val GOES_EXACT = setOf(
        "amenity", "shop", "craft", "office", "leisure", "tourism", "healthcare", "club",
        "name", "alt_name", "short_name", "official_name", "loc_name", "int_name", "old_name",
        "brand", "operator", "network", "cuisine", "opening_hours", "phone", "website", "url", "email", "fax",
        "facebook", "instagram", "twitter", "description", "wikidata", "wikipedia",
        "takeaway", "delivery", "drive_through", "drive_in", "outdoor_seating", "indoor_seating",
        "atm", "religion", "denomination", "sport", "beauty", "clothes", "vending", "dispensing", "stars", "rooms",
        "internet_access", "air_conditioning", "smoking", "reservation", "self_service", "organic", "bar",
        "microbrewery", "second_hand", "check_date", "survey:date", "start_date", "opening_date", "product", "books",
    )

    /** Key prefixes of the old business that go (its names in other languages, brand, contact, lifecycle forms ...). */
    val GOES_PREFIX = listOf(
        "name:", "brand:", "operator:", "contact:", "opening_hours:", "payment:", "diet:", "fuel:", "service:",
        "healthcare:", "disused:", "was:", "abandoned:", "check_date:", "internet_access:", "shop:", "drive_through:",
        "takeaway:", "delivery:", "website:", "phone:", "cuisine:",
    )

    /** Whether [key] belongs to the old business (it goes when another business takes the place). */
    fun goes(key: String): Boolean = key in GOES_EXACT || GOES_PREFIX.any { key.startsWith(it) }

    /** An object with an address and none of the business keys: the new business's tags go onto it. */
    fun isBareAddress(tags: Map<String, String>): Boolean =
        tags.keys.any { it.startsWith("addr:") } && PlaceEdits.MAIN_KEYS.none { it in tags } &&
            tags.keys.none { it.startsWith("disused:") || it.startsWith("was:") }

    /**
     * A business: an object with a main type key ([PlaceEdits.MAIN_KEYS]) or a closed one ("disused:shop").
     * Not a monument, a memorial, a statue, a sign or a viewpoint (the owner, 2026-11-21: they stand at a
     * spot without occupying it, so they are not "already on the map here" when a business is added).
     */
    fun isBusiness(tags: Map<String, String>): Boolean =
        PlaceEdits.MAIN_KEYS.any { it in tags && "$it=${tags[it]}" !in NOT_A_BUSINESS } ||
            tags.keys.any { k -> PlaceEdits.MAIN_KEYS.any { k == "disused:$it" } }

    /** Main-key values that are things to look at, not places that occupy a spot. */
    val NOT_A_BUSINESS = setOf("tourism=artwork", "tourism=information", "tourism=viewpoint", "tourism=attraction")

    /**
     * The same for a pack place's type: a category code of the search pack ("restaurant") is one; a raw
     * tag ("shop=florist") when its key is a main key and it is not in [NOT_A_BUSINESS]; "historic=memorial" is not.
     */
    fun isBusinessType(typeCode: String): Boolean {
        val key = typeCode.substringBefore('=', "")
        return if (key.isEmpty()) typeCode !in NOT_A_BUSINESS_CODES else key in PlaceEdits.MAIN_KEYS && typeCode !in NOT_A_BUSINESS
    }

    /** The search pack's category codes that are sights, natural places, parks and parking: not a business at a spot. */
    val NOT_A_BUSINESS_CODES = setOf(
        "viewpoint", "attraction", "picnic_site", "beach", "waterfall", "cave", "peak", "park", "state_park",
        "parking", "truck_parking", "park_and_ride",
    )

    /**
     * The changes that make [old] the new business [next] (its type tags, name, hours, phone, website):
     * every key of the old business that [next] does not set again goes (null), [next]'s values come.
     */
    fun replace(old: Map<String, String>, next: Map<String, String>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for (k in old.keys.sorted()) if (goes(k) && k !in next) out[k] = null
        for ((k, v) in next) if (old[k] != v) out[k] = v
        return out
    }

    /** The keys of [old] that stay (shown in the summary: "address kept"). */
    fun kept(old: Map<String, String>, next: Map<String, String>): List<String> = old.keys.filter { !goes(it) && it !in next }.sorted()
}
