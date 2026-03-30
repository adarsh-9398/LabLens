package com.armodel.app

import android.util.Log
import com.google.android.filament.Engine
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Color as SceneColor
import io.github.sceneview.math.Position
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.SphereNode
import kotlin.math.sqrt

/**
 * Renders a MoleculeData as a 3D ball-and-stick model using SceneView's
 * SphereNode (atoms) and CylinderNode (bonds).
 */
object MoleculeRenderer {

    private const val TAG = "MoleculeRenderer"

    /** Overall scale factor to convert Angstrom coordinates to AR world meters */
    private const val WORLD_SCALE = 0.08f

    /** Bond cylinder radius in world units */
    private const val BOND_RADIUS = 0.006f

    /**
     * Build a 3D node tree for the given molecule.
     */
    fun buildMoleculeNode(
        engine: Engine,
        materialLoader: MaterialLoader,
        molecule: MoleculeData
    ): Node {
        val parentNode = Node(engine)

        if (molecule.atoms.isEmpty()) {
            Log.w(TAG, "No atoms to render")
            return parentNode
        }

        // Center the molecule around the origin
        val centerX = molecule.atoms.map { it.x }.average().toFloat()
        val centerY = molecule.atoms.map { it.y }.average().toFloat()
        val centerZ = molecule.atoms.map { it.z }.average().toFloat()

        Log.d(TAG, "Building molecule with ${molecule.atoms.size} atoms, " +
                "${molecule.bonds.size} bonds, center=($centerX,$centerY,$centerZ)")

        // ── Build atom spheres ──────────────────────────────────────────

        for (atom in molecule.atoms) {
            try {
                val radius = AtomRadii.getRadius(atom.element) * WORLD_SCALE
                val colorFloats = AtomColors.getColorFloats(atom.element)
                val color = SceneColor(colorFloats[0], colorFloats[1], colorFloats[2], 1.0f)

                val mat = materialLoader.createColorInstance(
                    color = color,
                    metallic = 0.0f,
                    roughness = 0.4f,
                    reflectance = 0.5f
                )

                val atomNode = SphereNode(
                    engine = engine,
                    radius = radius,
                    center = Position(0f, 0f, 0f),
                    stacks = 12,
                    slices = 12,
                    materialInstance = mat,
                    builderApply = {
                        castShadows(false)
                        receiveShadows(true)
                        culling(false)
                    }
                )

                atomNode.position = Position(
                    (atom.x - centerX) * WORLD_SCALE,
                    (atom.y - centerY) * WORLD_SCALE,
                    (atom.z - centerZ) * WORLD_SCALE
                )

                parentNode.addChildNode(atomNode)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create atom node for ${atom.element} at index ${atom.index}", e)
            }
        }

        // ── Build bond cylinders ────────────────────────────────────────

        val bondColor = SceneColor(0.6f, 0.6f, 0.6f, 1.0f)

        for (bond in molecule.bonds) {
            try {
                if (bond.atomIndex1 < 0 || bond.atomIndex1 >= molecule.atoms.size ||
                    bond.atomIndex2 < 0 || bond.atomIndex2 >= molecule.atoms.size) {
                    continue
                }

                val a1 = molecule.atoms[bond.atomIndex1]
                val a2 = molecule.atoms[bond.atomIndex2]

                val x1 = (a1.x - centerX) * WORLD_SCALE
                val y1 = (a1.y - centerY) * WORLD_SCALE
                val z1 = (a1.z - centerZ) * WORLD_SCALE
                val x2 = (a2.x - centerX) * WORLD_SCALE
                val y2 = (a2.y - centerY) * WORLD_SCALE
                val z2 = (a2.z - centerZ) * WORLD_SCALE

                val dx = x2 - x1
                val dy = y2 - y1
                val dz = z2 - z1
                val length = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

                if (length < 0.001f) continue

                val midX = (x1 + x2) / 2f
                val midY = (y1 + y2) / 2f
                val midZ = (z1 + z2) / 2f

                val bondMat = materialLoader.createColorInstance(
                    color = bondColor,
                    metallic = 0.1f,
                    roughness = 0.6f,
                    reflectance = 0.3f
                )

                val bondNode = CylinderNode(
                    engine = engine,
                    radius = BOND_RADIUS,
                    height = length,
                    center = Position(0f, 0f, 0f),
                    sideCount = 8,
                    materialInstance = bondMat,
                    builderApply = {
                        castShadows(false)
                        receiveShadows(false)
                        culling(false)
                    }
                )

                bondNode.position = Position(midX, midY, midZ)

                // Rotate the cylinder (default Y-axis aligned) to point from a1 to a2
                val dirX = dx / length
                val dirY = dy / length
                val dirZ = dz / length
                bondNode.quaternion = quaternionFromTo(0f, 1f, 0f, dirX, dirY, dirZ)

                parentNode.addChildNode(bondNode)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create bond node", e)
            }
        }

        Log.d(TAG, "Built molecule: ${molecule.atoms.size} atoms, " +
                "${molecule.bonds.size} bonds, ${parentNode.childNodes.size} child nodes")
        return parentNode
    }

    /**
     * Compute a quaternion that rotates from direction (fx, fy, fz) to (tx, ty, tz).
     */
    private fun quaternionFromTo(
        fx: Float, fy: Float, fz: Float,
        tx: Float, ty: Float, tz: Float
    ): dev.romainguy.kotlin.math.Quaternion {
        val cx = fy * tz - fz * ty
        val cy = fz * tx - fx * tz
        val cz = fx * ty - fy * tx
        val dot = fx * tx + fy * ty + fz * tz

        if (dot < -0.999f) {
            var px = 0f; var py = 0f; var pz = 1f
            if (kotlin.math.abs(fz) > 0.9f) { px = 1f; pz = 0f }
            return dev.romainguy.kotlin.math.Quaternion(px, py, pz, 0f)
        }

        val s = sqrt(((1 + dot) * 2).toDouble()).toFloat()
        val invS = 1f / s

        return dev.romainguy.kotlin.math.Quaternion(
            x = cx * invS,
            y = cy * invS,
            z = cz * invS,
            w = s * 0.5f
        )
    }
}
