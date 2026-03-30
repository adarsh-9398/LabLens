package com.armodel.app

import android.graphics.Color

/**
 * Represents a single atom in a molecule with 3D coordinates.
 */
data class AtomData(
    val index: Int,
    val element: String,   // e.g. "C", "H", "O", "N"
    val x: Float,
    val y: Float,
    val z: Float
)

/**
 * Represents a bond between two atoms.
 */
data class BondData(
    val atomIndex1: Int,
    val atomIndex2: Int,
    val order: Int = 1     // 1=single, 2=double, 3=triple
)

/**
 * Full molecule data including atoms, bonds, and properties.
 */
data class MoleculeData(
    val cid: Int,
    val atoms: List<AtomData>,
    val bonds: List<BondData>,
    val properties: MoleculeProperties
)

/**
 * Molecule properties fetched from PubChem.
 */
data class MoleculeProperties(
    val cid: Int = 0,
    val iupacName: String = "",
    val molecularFormula: String = "",
    val molecularWeight: String = "",
    val commonName: String = "",
    val description: String = ""
)

/**
 * Represents a search result for disambiguation.
 */
data class MoleculeSearchResult(
    val cid: Int,
    val name: String,
    val formula: String,
    val molecularWeight: String
)

/**
 * CPK coloring convention for atoms – standard colors used in chemistry.
 */
object AtomColors {

    private val colorMap = mapOf(
        "H"  to Color.parseColor("#FFFFFF"),  // White
        "C"  to Color.parseColor("#333333"),  // Dark gray
        "N"  to Color.parseColor("#3050F8"),  // Blue
        "O"  to Color.parseColor("#FF0D0D"),  // Red
        "F"  to Color.parseColor("#90E050"),  // Green
        "Cl" to Color.parseColor("#1FF01F"),  // Green
        "Br" to Color.parseColor("#A62929"),  // Dark red
        "I"  to Color.parseColor("#940094"),  // Purple
        "S"  to Color.parseColor("#FFFF30"),  // Yellow
        "P"  to Color.parseColor("#FF8000"),  // Orange
        "Fe" to Color.parseColor("#E06633"),  // Orange-brown
        "Na" to Color.parseColor("#AB5CF2"),  // Purple
        "K"  to Color.parseColor("#8F40D4"),  // Purple
        "Ca" to Color.parseColor("#3DFF00"),  // Green
        "Mg" to Color.parseColor("#8AFF00"),  // Green
        "Zn" to Color.parseColor("#7D80B0"),  // Gray-blue
        "Cu" to Color.parseColor("#C88033"),  // Copper
        "Si" to Color.parseColor("#F0C8A0"),  // Beige
        "Al" to Color.parseColor("#BFA6A6"),  // Light gray
        "B"  to Color.parseColor("#FFB5B5"),  // Light pink
        "Li" to Color.parseColor("#CC80FF"),  // Light purple
        "He" to Color.parseColor("#D9FFFF"),  // Cyan-tint
        "Ne" to Color.parseColor("#B3E3F5"),  // Light blue
        "Ar" to Color.parseColor("#80D1E3"),  // Cyan
        "Co" to Color.parseColor("#F090A0"),  // Pink
        "Ni" to Color.parseColor("#50D050"),  // Green
        "Mn" to Color.parseColor("#9C7AC7"),  // Purple
        "Ti" to Color.parseColor("#BFC2C7"),  // Silver
        "Cr" to Color.parseColor("#8A99C7"),  // Steel blue
    )

    /** Default color for unknown elements */
    private val defaultColor = Color.parseColor("#FF69B4") // Hot pink

    fun getColor(element: String): Int {
        return colorMap[element] ?: defaultColor
    }

    /** Get color as float array [r, g, b, a] for Filament materials */
    fun getColorFloats(element: String): FloatArray {
        val color = getColor(element)
        return floatArrayOf(
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f,
            1.0f
        )
    }
}

/**
 * Atom radii in Angstroms (Van der Waals radii, scaled for visual appeal).
 */
object AtomRadii {

    private val radiiMap = mapOf(
        "H"  to 0.25f,
        "C"  to 0.40f,
        "N"  to 0.38f,
        "O"  to 0.36f,
        "F"  to 0.32f,
        "Cl" to 0.45f,
        "Br" to 0.50f,
        "I"  to 0.55f,
        "S"  to 0.50f,
        "P"  to 0.48f,
        "Fe" to 0.55f,
        "Na" to 0.55f,
        "K"  to 0.60f,
        "Ca" to 0.55f,
        "Mg" to 0.50f,
        "Co" to 0.50f,
        "Si" to 0.45f,
    )

    private const val defaultRadius = 0.40f

    fun getRadius(element: String): Float {
        return radiiMap[element] ?: defaultRadius
    }
}
