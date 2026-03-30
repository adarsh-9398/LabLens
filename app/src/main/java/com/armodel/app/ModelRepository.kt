package com.armodel.app

/**
 * Data class representing information about a 3D model.
 */
data class ModelInfo(
    val keyword: String,
    val modelFileName: String, // Local asset path (fallback)
    val modelUrl: String,      // Remote URL for reliable loading
    val displayName: String,
    val description: String,
    val scale: Float = 0.5f
)

/**
 * Result when checking if a detected word is a molecule/element keyword.
 */
data class MoleculeQuery(
    val searchTerm: String,
    val displayName: String
)

/**
 * Repository that maps detected words to their corresponding 3D model info.
 * Also detects molecule/element keywords for dynamic PubChem lookup.
 */
object ModelRepository {

    // Free GLB models hosted on GitHub/public CDNs
    private const val BASE_URL = "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models"

    private val models: Map<String, ModelInfo> = mapOf(
        "CAR" to ModelInfo(
            keyword = "CAR",
            modelFileName = "models/car.glb",
            modelUrl = "https://sceneview.github.io/assets/models/DamagedHelmet.glb",
            displayName = "Car",
            description = "A car is a wheeled motor vehicle used for transportation. " +
                    "Most definitions of cars say that they run primarily on roads, seat one to eight people, " +
                    "have four wheels, and mainly transport people rather than goods. " +
                    "The first automobile was invented in 1886 by Karl Benz.",
            scale = 0.5f
        ),
        "PLANE" to ModelInfo(
            keyword = "PLANE",
            modelFileName = "models/plane.glb",
            modelUrl = "$BASE_URL/FlightHelmet/glTF-Binary/FlightHelmet.glb",
            displayName = "Airplane",
            description = "An airplane is a powered, fixed-wing aircraft that is propelled forward by thrust " +
                    "from a jet engine or propeller. The Wright brothers made the first sustained, " +
                    "controlled flight on December 17, 1903. Modern planes can fly at speeds exceeding Mach 2.",
            scale = 0.5f
        ),
        "TREE" to ModelInfo(
            keyword = "TREE",
            modelFileName = "models/tree.glb",
            modelUrl = "$BASE_URL/SheenChair/glTF-Binary/SheenChair.glb",
            displayName = "Tree",
            description = "Trees are perennial plants with an elongated stem (trunk) that supports branches " +
                    "and leaves. Trees play a vital role in the ecosystem — a single large tree can provide " +
                    "a day's oxygen for up to 4 people. The oldest known tree is over 5,000 years old.",
            scale = 0.5f
        ),
        "HOUSE" to ModelInfo(
            keyword = "HOUSE",
            modelFileName = "models/house.glb",
            modelUrl = "$BASE_URL/Lantern/glTF-Binary/Lantern.glb",
            displayName = "House",
            description = "A house is a building that functions as a home for humans. " +
                    "Houses have been built since prehistoric times and have evolved from simple shelters " +
                    "to complex structures with plumbing, electricity, and modern amenities.",
            scale = 0.5f
        ),
        "DOG" to ModelInfo(
            keyword = "DOG",
            modelFileName = "models/dog.glb",
            modelUrl = "$BASE_URL/Fox/glTF-Binary/Fox.glb",
            displayName = "Dog",
            description = "Dogs are domesticated mammals, not natural wild animals. " +
                    "They were originally bred from wolves and have been selectively bred over centuries " +
                    "for various behaviors, sensory capabilities, and physical attributes. " +
                    "Dogs are often called 'man's best friend'.",
            scale = 0.5f
        ),
        "PHONE" to ModelInfo(
            keyword = "PHONE",
            modelFileName = "models/phone.glb",
            modelUrl = "$BASE_URL/AntiqueCamera/glTF-Binary/AntiqueCamera.glb",
            displayName = "Phone",
            description = "A mobile phone (smartphone) is a portable device that combines wireless telephone " +
                    "technology with computing. The first handheld mobile phone was demonstrated by " +
                    "Motorola in 1973. Today, over 6.8 billion people use smartphones worldwide.",
            scale = 0.5f
        ),
        "BOOK" to ModelInfo(
            keyword = "BOOK",
            modelFileName = "models/book.glb",
            modelUrl = "$BASE_URL/Avocado/glTF-Binary/Avocado.glb",
            displayName = "Book",
            description = "A book is a medium for recording information in the form of writing or images. " +
                    "The history of books dates back to ancient scrolls and codices. " +
                    "The Gutenberg Bible (c. 1455) was the first major book printed using movable type.",
            scale = 2.0f
        )
    )

    /**
     * Look up a model by keyword (case-insensitive).
     */
    fun findModel(keyword: String): ModelInfo? {
        return models[keyword.uppercase().trim()]
    }

    /**
     * Get all available keywords.
     */
    fun getAllKeywords(): Set<String> = models.keys

    /**
     * Check if a keyword has a matching model.
     */
    fun hasModel(keyword: String): Boolean {
        return models.containsKey(keyword.uppercase().trim())
    }

    // ─── Molecule / Element Detection ──────────────────────────────────

    /**
     * Common molecule names mapped to their PubChem search term.
     */
    private val moleculeNames = mapOf(
        "WATER" to "water",
        "ETHANOL" to "ethanol",
        "METHANOL" to "methanol",
        "ASPIRIN" to "aspirin",
        "CAFFEINE" to "caffeine",
        "GLUCOSE" to "glucose",
        "SUCROSE" to "sucrose",
        "SALT" to "sodium chloride",
        "AMMONIA" to "ammonia",
        "METHANE" to "methane",
        "ETHANE" to "ethane",
        "PROPANE" to "propane",
        "BUTANE" to "butane",
        "BENZENE" to "benzene",
        "TOLUENE" to "toluene",
        "ACETONE" to "acetone",
        "ACETIC ACID" to "acetic acid",
        "PENICILLIN" to "penicillin",
        "IBUPROFEN" to "ibuprofen",
        "PARACETAMOL" to "acetaminophen",
        "ACETAMINOPHEN" to "acetaminophen",
        "DOPAMINE" to "dopamine",
        "SEROTONIN" to "serotonin",
        "ADRENALINE" to "epinephrine",
        "INSULIN" to "insulin",
        "CHOLESTEROL" to "cholesterol",
        "NICOTINE" to "nicotine",
        "MORPHINE" to "morphine",
        "VITAMIN C" to "ascorbic acid",
        "CITRIC ACID" to "citric acid",
        "SULFURIC ACID" to "sulfuric acid",
        "HYDROCHLORIC ACID" to "hydrochloric acid",
        "NITRIC ACID" to "nitric acid",
        "DNA" to "adenine",
        "ATP" to "adenosine triphosphate",
    )

    /**
     * Common molecular formulas mapped to their PubChem search term.
     */
    private val moleculeFormulas = mapOf(
        "H2O" to "water",
        "CO2" to "carbon dioxide",
        "O2" to "oxygen",
        "N2" to "nitrogen",
        "H2" to "hydrogen",
        "CH4" to "methane",
        "C2H6" to "ethane",
        "C2H5OH" to "ethanol",
        "C2H6O" to "ethanol",
        "NH3" to "ammonia",
        "HCL" to "hydrochloric acid",
        "NACL" to "sodium chloride",
        "H2SO4" to "sulfuric acid",
        "HNO3" to "nitric acid",
        "C6H12O6" to "glucose",
        "C6H8O7" to "citric acid",
        "C8H10N4O2" to "caffeine",
        "C9H8O4" to "aspirin",
        "C3H6O" to "acetone",
        "C2H4O2" to "acetic acid",
        "C6H6" to "benzene",
        "C10H15NO" to "epinephrine",
    )

    /**
     * Element names mapped to their PubChem search term (searches for the element).
     */
    private val elementNames = mapOf(
        "HYDROGEN" to "hydrogen",
        "HELIUM" to "helium",
        "LITHIUM" to "lithium",
        "CARBON" to "carbon",
        "NITROGEN" to "nitrogen",
        "OXYGEN" to "oxygen",
        "FLUORINE" to "fluorine",
        "NEON" to "neon",
        "SODIUM" to "sodium",
        "MAGNESIUM" to "magnesium",
        "ALUMINUM" to "aluminum",
        "SILICON" to "silicon",
        "PHOSPHORUS" to "phosphorus",
        "SULFUR" to "sulfur",
        "CHLORINE" to "chlorine",
        "ARGON" to "argon",
        "POTASSIUM" to "potassium",
        "CALCIUM" to "calcium",
        "IRON" to "iron",
        "COPPER" to "copper",
        "ZINC" to "zinc",
        "BROMINE" to "bromine",
        "SILVER" to "silver",
        "IODINE" to "iodine",
        "GOLD" to "gold",
        "MERCURY" to "mercury",
        "LEAD" to "lead",
        "URANIUM" to "uranium",
    )

    /**
     * Check if a word is a molecule/element keyword and return the search query.
     * Applies OCR error correction (e.g. 0→O, l→I) for handwritten text.
     * Returns null if not a molecule keyword.
     */
    fun findMoleculeQuery(word: String): MoleculeQuery? {
        val upper = word.uppercase().trim()

        // Try the word as-is first
        val directMatch = tryMatchMolecule(upper, word)
        if (directMatch != null) return directMatch

        // Apply OCR error corrections and try again
        for (corrected in ocrCorrections(upper)) {
            val match = tryMatchMolecule(corrected, word)
            if (match != null) return match
        }

        return null
    }

    /** Try to match a (possibly corrected) uppercase string against our molecule databases */
    private fun tryMatchMolecule(upper: String, originalWord: String): MoleculeQuery? {
        // Check common molecule names
        moleculeNames[upper]?.let { searchTerm ->
            return MoleculeQuery(searchTerm = searchTerm, displayName = originalWord)
        }

        // Check molecular formulas
        moleculeFormulas[upper]?.let { searchTerm ->
            return MoleculeQuery(searchTerm = searchTerm, displayName = originalWord)
        }

        // Check element names
        elementNames[upper]?.let { searchTerm ->
            return MoleculeQuery(searchTerm = searchTerm, displayName = originalWord)
        }

        // If the word looks like a chemical formula, try it as a PubChem search
        if (looksLikeFormula(upper)) {
            return MoleculeQuery(searchTerm = upper, displayName = originalWord)
        }

        return null
    }

    /**
     * Generate OCR-corrected variants of the input text.
     * Handles common misreads in handwritten text:
     * - 0 (zero) ↔ O (letter)
     * - l (lowercase L) → I
     * - 1 (one) → I or l
     * - 5 → S
     * - 8 → B
     */
    private fun ocrCorrections(text: String): List<String> {
        val corrections = mutableSetOf<String>()

        // Replace all zeros with O (common: "H20" → "H2O")
        if (text.contains('0')) {
            corrections.add(text.replace('0', 'O'))
        }

        // Replace trailing O with 0 in case it's a number (e.g. "CO2" detected as "COZ")
        // Replace O after digits with O (it's likely correct already)

        // Replace lowercase L with I
        val withL = text.replace('L', 'I')  // Already uppercase
        if (withL != text) corrections.add(withL)

        // Replace 1 with I (e.g. "N1TROGEN" → "NITROGEN")
        if (text.contains('1')) {
            corrections.add(text.replace('1', 'I'))
        }

        // Replace 5 with S
        if (text.contains('5')) {
            corrections.add(text.replace('5', 'S'))
        }

        // Replace 8 with B
        if (text.contains('8')) {
            corrections.add(text.replace('8', 'B'))
        }

        // Combined: zeros to O AND other fixes
        if (text.contains('0') && text.contains('1')) {
            corrections.add(text.replace('0', 'O').replace('1', 'I'))
        }

        return corrections.toList()
    }

    /**
     * Simple heuristic: does this string look like a molecular formula?
     * Matches patterns like H2O, CO2, NaCl, C6H12O6, etc.
     */
    private fun looksLikeFormula(text: String): Boolean {
        if (text.length < 2 || text.length > 20) return false
        // Must start with an uppercase letter
        if (!text[0].isUpperCase()) return false
        // Must contain at least one digit or lowercase letter after uppercase
        val hasDigit = text.any { it.isDigit() }
        val pattern = Regex("^[A-Z][a-z]?\\d*([A-Z][a-z]?\\d*)*$")
        return hasDigit && pattern.matches(text)
    }
}
