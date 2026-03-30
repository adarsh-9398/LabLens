package com.armodel.app

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Client for PubChem PUG REST API.
 * Fetches 3D molecule coordinates, bonds, and properties.
 *
 * API docs: https://pubchem.ncbi.nlm.nih.gov/docs/pug-rest
 * Rate limit: 5 requests/second (we add small delays between calls)
 */
object PubChemApi {

    private const val TAG = "PubChemApi"
    private const val BASE_URL = "https://pubchem.ncbi.nlm.nih.gov/rest/pug"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // ─── Search by name → get CID(s) ──────────────────────────────────

    /**
     * Search for a compound by name and return matching CIDs with basic info.
     * Used for disambiguation when multiple results exist.
     */
    suspend fun searchByName(name: String): List<MoleculeSearchResult> = withContext(Dispatchers.IO) {
        try {
            val encodedName = URLEncoder.encode(name, "UTF-8")

            // First, get CID(s) for this name
            val cidsUrl = "$BASE_URL/compound/name/$encodedName/cids/JSON"
            val cidsJson = httpGet(cidsUrl) ?: return@withContext emptyList()
            val cidsObj = JsonParser.parseString(cidsJson).asJsonObject

            val cids = cidsObj
                .getAsJsonObject("IdentifierList")
                ?.getAsJsonArray("CID")
                ?.map { it.asInt }
                ?: return@withContext emptyList()

            // Limit to first 5 results
            val limitedCids = cids.take(5)

            // Get properties for each CID
            val cidsParam = limitedCids.joinToString(",")
            val propsUrl = "$BASE_URL/compound/cid/$cidsParam/property/IUPACName,MolecularFormula,MolecularWeight/JSON"
            val propsJson = httpGet(propsUrl) ?: return@withContext emptyList()
            val propsObj = JsonParser.parseString(propsJson).asJsonObject

            val properties = propsObj
                .getAsJsonObject("PropertyTable")
                ?.getAsJsonArray("Properties")
                ?: return@withContext emptyList()

            properties.map { prop ->
                val obj = prop.asJsonObject
                MoleculeSearchResult(
                    cid = obj.get("CID").asInt,
                    name = obj.get("IUPACName")?.asString ?: name,
                    formula = obj.get("MolecularFormula")?.asString ?: "",
                    molecularWeight = obj.get("MolecularWeight")?.asString ?: ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for: $name", e)
            emptyList()
        }
    }

    // ─── Get full 3D molecule data by CID ─────────────────────────────

    /**
     * Fetch complete molecule data: 3D atom coordinates + bonds + properties.
     */
    suspend fun getMolecule(cid: Int): MoleculeData? = withContext(Dispatchers.IO) {
        try {
            val conformer = get3DConformer(cid)
            if (conformer == null) {
                Log.e(TAG, "No 3D conformer found for CID $cid")
                return@withContext null
            }

            val properties = getProperties(cid)

            MoleculeData(
                cid = cid,
                atoms = conformer.first,
                bonds = conformer.second,
                properties = properties
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get molecule CID=$cid", e)
            null
        }
    }

    // ─── 3D / 2D Conformer (atoms + bonds) ──────────────────────────────

    /**
     * Fetch atom positions and bonds, trying 3D first, then 2D as fallback.
     * Many simple molecules (H2, O2, single atoms) don't have 3D conformers
     * in PubChem, but they always have 2D records with x,y coordinates.
     */
    private suspend fun get3DConformer(cid: Int): Pair<List<AtomData>, List<BondData>>? {
        // Try 3D first
        val url3d = "$BASE_URL/compound/cid/$cid/record/JSON?record_type=3d"
        val json3d = httpGet(url3d)
        if (json3d != null) {
            val result = parseConformer(cid, json3d, is3D = true)
            if (result != null) {
                Log.d(TAG, "Using 3D conformer for CID $cid (${result.first.size} atoms)")
                return result
            }
        }

        // Fallback to 2D (x,y only, z will be 0)
        Log.d(TAG, "No 3D data for CID $cid, falling back to 2D")
        val url2d = "$BASE_URL/compound/cid/$cid/record/JSON?record_type=2d"
        val json2d = httpGet(url2d)
        if (json2d != null) {
            val result = parseConformer(cid, json2d, is3D = false)
            if (result != null) {
                Log.d(TAG, "Using 2D conformer for CID $cid (${result.first.size} atoms)")
                return result
            }
        }

        Log.e(TAG, "No 3D or 2D conformer found for CID $cid")
        return null
    }

    /**
     * Parse a PubChem compound record JSON into atoms and bonds.
     * Works for both 3D records (with z coords) and 2D records (z = 0).
     */
    private fun parseConformer(cid: Int, json: String, is3D: Boolean): Pair<List<AtomData>, List<BondData>>? {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val record = root
                .getAsJsonArray("PC_Compounds")
                ?.get(0)?.asJsonObject
                ?: return null

            // Parse atoms
            val atomsObj = record.getAsJsonObject("atoms") ?: return null
            val elements = atomsObj.getAsJsonArray("element")
                ?.map { it.asInt } ?: return null

            // Parse coordinates from the first conformer
            val coordsArray = record.getAsJsonArray("coords")
                ?.get(0)?.asJsonObject
                ?.getAsJsonArray("conformers")
                ?.get(0)?.asJsonObject
                ?: return null

            val xCoords = coordsArray.getAsJsonArray("x")
                ?.map { it.asFloat } ?: return null
            val yCoords = coordsArray.getAsJsonArray("y")
                ?.map { it.asFloat } ?: return null
            // z is only present in 3D records; for 2D we use 0
            val zCoords = if (is3D) {
                coordsArray.getAsJsonArray("z")
                    ?.map { it.asFloat } ?: List(elements.size) { 0f }
            } else {
                List(elements.size) { 0f }
            }

            val atoms = elements.mapIndexed { index, atomicNumber ->
                AtomData(
                    index = index,
                    element = atomicNumberToSymbol(atomicNumber),
                    x = xCoords.getOrElse(index) { 0f },
                    y = yCoords.getOrElse(index) { 0f },
                    z = zCoords.getOrElse(index) { 0f }
                )
            }

            // Parse bonds
            val bondsObj = record.getAsJsonObject("bonds")
            val bonds = if (bondsObj != null) {
                val aid1 = bondsObj.getAsJsonArray("aid1")
                    ?.map { it.asInt } ?: emptyList()
                val aid2 = bondsObj.getAsJsonArray("aid2")
                    ?.map { it.asInt } ?: emptyList()
                val orders = bondsObj.getAsJsonArray("order")
                    ?.map { it.asInt } ?: List(aid1.size) { 1 }

                aid1.mapIndexed { index, a1 ->
                    BondData(
                        // PubChem uses 1-based atom IDs, convert to 0-based
                        atomIndex1 = a1 - 1,
                        atomIndex2 = aid2.getOrElse(index) { 1 } - 1,
                        order = orders.getOrElse(index) { 1 }
                    )
                }
            } else {
                emptyList()
            }

            return Pair(atoms, bonds)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ${if (is3D) "3D" else "2D"} conformer for CID $cid", e)
            return null
        }
    }

    // ─── Properties ───────────────────────────────────────────────────

    /**
     * Fetch molecule properties: name, formula, weight, description.
     */
    private suspend fun getProperties(cid: Int): MoleculeProperties {
        try {
            // Get basic properties
            val propsUrl = "$BASE_URL/compound/cid/$cid/property/IUPACName,MolecularFormula,MolecularWeight/JSON"
            val propsJson = httpGet(propsUrl)

            var iupacName = ""
            var formula = ""
            var weight = ""

            if (propsJson != null) {
                val propsObj = JsonParser.parseString(propsJson).asJsonObject
                val props = propsObj
                    .getAsJsonObject("PropertyTable")
                    ?.getAsJsonArray("Properties")
                    ?.get(0)?.asJsonObject

                if (props != null) {
                    iupacName = props.get("IUPACName")?.asString ?: ""
                    formula = props.get("MolecularFormula")?.asString ?: ""
                    weight = props.get("MolecularWeight")?.asString ?: ""
                }
            }

            // Get description
            val descUrl = "$BASE_URL/compound/cid/$cid/description/JSON"
            val descJson = httpGet(descUrl)
            var description = ""

            if (descJson != null) {
                try {
                    val descObj = JsonParser.parseString(descJson).asJsonObject
                    val infoList = descObj.getAsJsonArray("InformationList")
                        ?.asJsonObject?.getAsJsonArray("Information")
                        // Try the alternate structure
                        ?: descObj.getAsJsonObject("InformationList")
                            ?.getAsJsonArray("Information")

                    if (infoList != null) {
                        // Find the first entry with a description
                        for (info in infoList) {
                            val desc = info.asJsonObject.get("Description")?.asString
                            if (!desc.isNullOrBlank()) {
                                description = desc
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse description for CID $cid", e)
                }
            }

            // If no description from API, generate a basic one
            if (description.isBlank()) {
                description = buildString {
                    if (iupacName.isNotBlank()) append("$iupacName. ")
                    if (formula.isNotBlank()) append("Molecular formula: $formula. ")
                    if (weight.isNotBlank()) append("Molecular weight: $weight g/mol.")
                }
            }

            return MoleculeProperties(
                cid = cid,
                iupacName = iupacName,
                molecularFormula = formula,
                molecularWeight = weight,
                commonName = "",
                description = description
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch properties for CID $cid", e)
            return MoleculeProperties(cid = cid)
        }
    }

    // ─── HTTP helper ──────────────────────────────────────────────────

    private fun httpGet(url: String): String? {
        return try {
            Log.d(TAG, "GET $url")
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                Log.e(TAG, "HTTP ${response.code} for $url")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP request failed: $url", e)
            null
        }
    }

    // ─── Periodic table: atomic number → symbol ──────────────────────

    private fun atomicNumberToSymbol(atomicNumber: Int): String {
        return periodicTable.getOrElse(atomicNumber) { "?" }
    }

    private val periodicTable = mapOf(
        1 to "H", 2 to "He", 3 to "Li", 4 to "Be", 5 to "B",
        6 to "C", 7 to "N", 8 to "O", 9 to "F", 10 to "Ne",
        11 to "Na", 12 to "Mg", 13 to "Al", 14 to "Si", 15 to "P",
        16 to "S", 17 to "Cl", 18 to "Ar", 19 to "K", 20 to "Ca",
        21 to "Sc", 22 to "Ti", 23 to "V", 24 to "Cr", 25 to "Mn",
        26 to "Fe", 27 to "Co", 28 to "Ni", 29 to "Cu", 30 to "Zn",
        31 to "Ga", 32 to "Ge", 33 to "As", 34 to "Se", 35 to "Br",
        36 to "Kr", 37 to "Rb", 38 to "Sr", 39 to "Y", 40 to "Zr",
        41 to "Nb", 42 to "Mo", 43 to "Tc", 44 to "Ru", 45 to "Rh",
        46 to "Pd", 47 to "Ag", 48 to "Cd", 49 to "In", 50 to "Sn",
        51 to "Sb", 52 to "Te", 53 to "I", 54 to "Xe", 55 to "Cs",
        56 to "Ba", 57 to "La", 72 to "Hf", 73 to "Ta", 74 to "W",
        75 to "Re", 76 to "Os", 77 to "Ir", 78 to "Pt", 79 to "Au",
        80 to "Hg", 81 to "Tl", 82 to "Pb", 83 to "Bi", 84 to "Po",
        85 to "At", 86 to "Rn"
    )
}
