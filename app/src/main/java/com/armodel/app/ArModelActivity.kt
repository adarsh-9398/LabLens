package com.armodel.app

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.view.Choreographer
import android.view.ScaleGestureDetector
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.getUpdatedPlanes
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.MaterialLoader
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import com.armodel.app.databinding.ActivityArModelBinding
import kotlinx.coroutines.launch

class ArModelActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ArModelActivity"
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }

    private lateinit var binding: ActivityArModelBinding
    private lateinit var textRecognitionManager: TextRecognitionManager
    private lateinit var rotationDetector: RotationGestureDetector
    private lateinit var scaleDetector: ScaleGestureDetector

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var rotationVelocityX = 0f
    private var rotationVelocityY = 0f
    private val momentumDamping = 0.92f
    private val choreographer = Choreographer.getInstance()
    private val momentumFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (Math.abs(rotationVelocityX) > 0.01f || Math.abs(rotationVelocityY) > 0.01f) {
                applyRotationDelta(rotationVelocityX, rotationVelocityY)
                rotationVelocityX *= momentumDamping
                rotationVelocityY *= momentumDamping
                choreographer.postFrameCallback(this)
            }
        }
    }

    private var currentAnchorNode: AnchorNode? = null
    private var currentModelNode: Node? = null
    
    private var currentModelInfo: ModelInfo? = null
    private var currentMoleculeProperties: MoleculeProperties? = null
    
    private var isModelPlaced = false
    private var isInfoPanelVisible = false
    private var isFetchingMolecule = false
    private var isPlainMode = false
    private var isArSupported = false

    // Stores pending model info to place when a plane is found
    private var pendingModelInfo: ModelInfo? = null
    // Stores a pending molecule node to place when a plane is found
    private var pendingMoleculeNode: Pair<Node, MoleculeProperties>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityArModelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        textRecognitionManager = TextRecognitionManager()

        setupPlainScene()

        when (ArCoreApk.getInstance().checkAvailability(this)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                isArSupported = true
                binding.btnToggleAr.visibility = View.VISIBLE
                if (hasCameraPermission()) {
                    initArSceneView()
                } else {
                    requestCameraPermission()
                }
            }
            else -> {
                handleArUnavailable()
            }
        }

        setupUI()
        setupGestures()
    }

    // ─── Permission Handling ────────────────────────────────────────────

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initArSceneView()
            } else {
                Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ─── Setup ───────────────────────────────────────────────────────

    private fun setupPlainScene() {
        binding.sceneView.lifecycle = this@ArModelActivity.lifecycle
        binding.sceneView.setBackgroundColor(android.graphics.Color.WHITE)

        val engine = binding.sceneView.engine

        val mainLightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f)
            .intensity(100_000f)
            .direction(0f, -1f, -0.5f)
            .build(engine, mainLightEntity)
        binding.sceneView.addChildNode(LightNode(engine, mainLightEntity))

        val fillLightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.8f, 0.85f, 1f)
            .intensity(40_000f)
            .direction(0f, 1f, 0.3f)
            .build(engine, fillLightEntity)
        binding.sceneView.addChildNode(LightNode(engine, fillLightEntity))
    }

    private fun handleArUnavailable() {
        isArSupported = false
        binding.arSceneView.visibility = View.GONE
        binding.btnToggleAr.visibility = View.GONE
        binding.btnToggleAr.isEnabled = false
        setMode(true)
        Toast.makeText(this, "AR not available — running in Plain mode", Toast.LENGTH_LONG).show()
    }

    private fun initArSceneView() {
        try {
            val sceneView = binding.arSceneView
            sceneView.lifecycle = this@ArModelActivity.lifecycle
            sceneView.planeRenderer.isEnabled = true

            sceneView.configureSession { session, config ->
                config.depthMode = when (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    true -> Config.DepthMode.AUTOMATIC
                    else -> Config.DepthMode.DISABLED
                }
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            }

            sceneView.onSessionUpdated = { _, frame ->
                if (!isModelPlaced) {
                    val pendingMolecule = pendingMoleculeNode
                    if (pendingMolecule != null) {
                        tryPlaceMoleculeNode(frame, pendingMolecule)
                    } else if (!isFetchingMolecule) {
                        val pending = pendingModelInfo
                        if (pending != null) {
                            tryPlaceModel(frame, pending)
                        } else {
                            processFrameForText(frame)
                        }
                    }
                }
            }
        } catch (e: UnavailableArcoreNotInstalledException) {
            handleArUnavailable()
        } catch (e: UnavailableDeviceNotCompatibleException) {
            handleArUnavailable()
        } catch (e: Exception) {
            handleArUnavailable()
        }
    }

    private fun setupGestures() {
        rotationDetector = RotationGestureDetector(object : RotationGestureDetector.OnRotationGestureListener {
            override fun onRotation(detector: RotationGestureDetector): Boolean {
                currentModelNode?.let { node ->
                    val rot = node.rotation
                    node.rotation = Rotation(rot.x, rot.y, rot.z - detector.getAngleDelta())
                }
                return true
            }
        })

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentModelNode?.let { node ->
                    val scaleFactor = detector.scaleFactor
                    val currentScale = node.scale.x
                    val newScale = (currentScale * scaleFactor).coerceIn(0.01f, 10f)
                    node.scale = Scale(newScale)
                }
                return true
            }
        })

        val touchListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Kill any active momentum when finger touches down
                    choreographer.removeFrameCallback(momentumFrameCallback)
                    rotationVelocityX = 0f
                    rotationVelocityY = 0f
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        
                        // Small threshold to avoid jitter on micro-moves
                        if (Math.abs(dx) > 1f || Math.abs(dy) > 1f) {
                            rotationVelocityX = dy * 0.35f
                            rotationVelocityY = dx * 0.35f
                            applyRotationDelta(rotationVelocityX, rotationVelocityY)
                            lastTouchX = event.x
                            lastTouchY = event.y
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    choreographer.postFrameCallback(momentumFrameCallback)
                }
            }
            
            // 2-finger events: split by gesture type
            if (event.pointerCount == 2) {
                scaleDetector.onTouchEvent(event)       // handles pinch → scale
                rotationDetector.onTouchEvent(event)    // handles twist → Z-axis
                return@OnTouchListener true            // do NOT pass to SceneView
            }
            true
        }
        
        binding.arSceneView.setOnTouchListener(touchListener)
        binding.sceneView.setOnTouchListener(touchListener)
    }

    private fun applyRotationDelta(deltaX: Float, deltaY: Float) {
        currentModelNode?.let { node ->
            node.rotation = Rotation(
                node.rotation.x + deltaX,
                node.rotation.y + deltaY,
                node.rotation.z
            )
        }
    }


    private fun setupUI() {
        // Toggles
        binding.btnToggleAr.setOnClickListener { setMode(false) }
        binding.btnTogglePlain.setOnClickListener { setMode(true) }
        setMode(false) // Initial state

        binding.btnReset.setOnClickListener { resetScene() }
        
        // Tabs
        binding.tabStructure.setOnClickListener { selectTab(0) }
        binding.tabBonds.setOnClickListener { selectTab(1) }
        binding.tabPolarity.setOnClickListener { selectTab(2) }
        binding.tabUses.setOnClickListener { selectTab(3) }
        selectTab(0) // Default
    }

    private fun selectTab(index: Int) {
        val tabs = listOf(binding.tabStructure, binding.tabBonds, binding.tabPolarity, binding.tabUses)
        tabs.forEachIndexed { i, view -> view.isSelected = (i == index) }
        
        binding.tabContentStructure.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.tabContentBonds.visibility = if (index == 1) View.VISIBLE else View.GONE
        // Polarity and Uses could have their own containers, or reuse infoDetail
        
        val desc = currentMoleculeProperties?.description?.ifBlank { "Structure information not available." } 
                   ?: currentModelInfo?.description 
                   ?: "Structure information not available."
        
        when(index) {
            0 -> binding.infoDetail.text = desc
            1 -> {
                binding.tabContentBonds.visibility = View.VISIBLE
                binding.tabContentStructure.visibility = View.GONE
                binding.tabContentBonds.text = "Bond lengths and angles vary by element. O-H bonds are typically 96 pm."
            }
            2 -> {
                binding.tabContentStructure.visibility = View.VISIBLE
                binding.tabContentBonds.visibility = View.GONE
                binding.infoDetail.text = "Properties derived from electronegativity differences between bonded atoms."
            }
            3 -> {
                binding.tabContentStructure.visibility = View.VISIBLE
                binding.tabContentBonds.visibility = View.GONE
                binding.infoDetail.text = "Real-world applications and uses of this chemical compound."
            }
        }
    }

    private fun setMode(plainMode: Boolean) {
        if (!isModelPlaced && plainMode && isArSupported) {
            Toast.makeText(this, "Scan a molecule first to enter Plain mode", Toast.LENGTH_SHORT).show()
            return
        }
        
        isPlainMode = plainMode
        if (plainMode) {
            binding.arSceneView.visibility = View.GONE
            binding.sceneView.visibility = View.VISIBLE
            
            binding.arBadge.text = "Plain"
            binding.arBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_ar_badge_off)
            binding.arBadge.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            
            binding.btnToggleAr.isSelected = false
            binding.btnTogglePlain.isSelected = true
            
            // Move model to plain scene
            currentModelNode?.let { node ->
                currentAnchorNode?.removeChildNode(node)
                node.parent = null
                binding.sceneView.addChildNode(node)

                // Place model directly at origin for Plain mode
                node.position = Position(x = 0f, y = 0f, z = 0f)
                node.rotation = Rotation(0f, 0f, 0f)
                node.scale = Scale(0.4f)

                // Place camera 1.2m away and look at origin
                binding.sceneView.cameraNode.position = Position(0f, 0f, 1.2f)
                binding.sceneView.cameraNode.lookAt(Position(0f, 0f, 0f))
                
                binding.sceneView.setBackgroundColor(android.graphics.Color.WHITE)
                
                animatePlainModeEntry(node)
                updateChipsAndProps()
            }
        } else {
            // AR mode
            binding.sceneView.visibility = View.GONE
            binding.arSceneView.visibility = View.VISIBLE
            
            binding.arBadge.text = "AR"
            binding.arBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_ar_badge_on)
            binding.arBadge.setTextColor(ContextCompat.getColor(this, R.color.text_light))
            
            binding.btnToggleAr.isSelected = true
            binding.btnTogglePlain.isSelected = false
            
            // Move model back to AR anchor if exists
            currentModelNode?.let { node ->
                binding.sceneView.removeChildNode(node)
                node.position = Position(0f, 0f, 0f) // local to anchor
                
                if (currentAnchorNode != null && node.parent != currentAnchorNode) {
                    currentAnchorNode!!.addChildNode(node)
                }
            }
        }
    }

    private fun animatePlainModeEntry(node: Node) {
        // Start small, animate to close-up scale
        node.scale = Scale(0.05f)

        ValueAnimator.ofFloat(0.05f, 0.45f).apply {
            duration = 400
            interpolator = OvershootInterpolator(1.3f)
            addUpdateListener {
                node.scale = Scale(it.animatedValue as Float)
            }
            start()
        }

        // Slide the bottom card up simultaneously
        binding.infoCard.translationY = binding.infoCard.height.toFloat()
        binding.infoCard.animate()
            .translationY(0f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // ─── Text Recognition Pipeline ─────────────────────────────────────

    private fun processFrameForText(frame: Frame) {
        try {
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) return

            val image = frame.acquireCameraImage()

            // Get device rotation for proper image orientation
            val rotation = display?.rotation ?: android.view.Surface.ROTATION_0
            val rotationDegrees = when (rotation) {
                android.view.Surface.ROTATION_0 -> 90
                android.view.Surface.ROTATION_90 -> 0
                android.view.Surface.ROTATION_180 -> 270
                android.view.Surface.ROTATION_270 -> 180
                else -> 90
            }

            val accepted = textRecognitionManager.processFrame(
                image,
                rotationDegrees,
                object : TextRecognitionManager.OnTextDetectedListener {
                    override fun onTextDetected(detectedWords: List<String>) {
                        handleDetectedWords(detectedWords)
                    }

                    override fun onError(error: Exception) {
                        Log.e(TAG, "Text recognition error", error)
                    }
                }
            )

            // If the frame was throttled/skipped, we must close the image ourselves
            if (!accepted) {
                image.close()
            }
            // If accepted, TextRecognitionManager takes ownership and will close it
        } catch (e: Exception) {
            // Frame might not have image available; that's OK
            Log.d(TAG, "Could not acquire camera image: ${e.message}")
        }
    }

    private fun handleDetectedWords(words: List<String>) {
        if (isModelPlaced || isFetchingMolecule || isPlainMode) return

        if (words.isNotEmpty()) {
            runOnUiThread {
                val preview = words.take(4).joinToString(", ")
                binding.scanStatusText.text = "Seeing: $preview"
            }
        }

        for (word in words) {
            // 1. Check for molecule/element keywords FIRST
            val moleculeQuery = ModelRepository.findMoleculeQuery(word)
            if (moleculeQuery != null) {
                Log.d(TAG, "Molecule match! word='$word' → search='${moleculeQuery.searchTerm}'")
                runOnUiThread {
                    showDetectedWord("🧪 ${word.uppercase()}")
                    fetchAndPlaceMolecule(moleculeQuery)
                }
                return
            }

            // 2. Check for regular GLB model keywords
            val modelInfo = ModelRepository.findModel(word)
            if (modelInfo != null) {
                Log.d(TAG, "GLB model match! word='$word' → model='${modelInfo.displayName}'")
                runOnUiThread {
                    showDetectedWord(word)
                    pendingModelInfo = modelInfo
                }
                return
            }
        }

        // Show the first detected word on the chip even if no model found
        if (words.isNotEmpty()) {
            runOnUiThread {
                showDetectedWord(words.first())
            }
        }
    }

    private fun showDetectedWord(word: String) {
        binding.detectedWordChip.apply {
            text = getString(R.string.detected_label, word.uppercase())
            visibility = View.VISIBLE

            // Animate in
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .start()
        }
    }

    // ─── Molecule Fetch & Placement ────────────────────────────────────

    private fun fetchAndPlaceMolecule(query: MoleculeQuery) {
        if (isFetchingMolecule) return
        isFetchingMolecule = true

        binding.moleculeLoadingIndicator.visibility = View.VISIBLE
        binding.scanProgress.visibility = View.GONE
        binding.scanStatusText.text = getString(R.string.loading_molecule)
        
        binding.btnReset.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // Search PubChem for this molecule
                val results = PubChemApi.searchByName(query.searchTerm)

                if (results.isEmpty()) {
                    runOnUiThread {
                        isFetchingMolecule = false
                        binding.moleculeLoadingIndicator.visibility = View.GONE
                        binding.scanProgress.visibility = View.VISIBLE
                        binding.scanStatusText.text = getString(R.string.scanning_text)
                        binding.btnReset.visibility = View.GONE
                        Toast.makeText(
                            this@ArModelActivity,
                            getString(R.string.molecule_fetch_error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                // If multiple distinct results, show disambiguation dialog
                if (results.size > 1 && hasDistinctFormulas(results)) {
                    runOnUiThread {
                        showDisambiguationDialog(results)
                    }
                } else {
                    // Single result — fetch and render directly
                    loadAndRenderMolecule(results.first().cid)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch molecule: ${query.searchTerm}", e)
                runOnUiThread {
                    isFetchingMolecule = false
                    binding.moleculeLoadingIndicator.visibility = View.GONE
                    binding.scanProgress.visibility = View.VISIBLE
                    binding.scanStatusText.text = getString(R.string.scanning_text)
                    binding.btnReset.visibility = View.GONE
                    Toast.makeText(
                        this@ArModelActivity,
                        getString(R.string.molecule_fetch_error),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun hasDistinctFormulas(results: List<MoleculeSearchResult>): Boolean {
        val formulas = results.map { it.formula.uppercase() }.toSet()
        return formulas.size > 1
    }

    private fun showDisambiguationDialog(results: List<MoleculeSearchResult>) {
        val items = results.map { result ->
            "${result.name}\n   ${result.formula} — ${result.molecularWeight} g/mol"
        }.toTypedArray()

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.disambiguation_title))
            .setItems(items) { _, which ->
                val selected = results[which]
                lifecycleScope.launch {
                    loadAndRenderMolecule(selected.cid)
                }
            }
            .setOnCancelListener {
                isFetchingMolecule = false
                binding.moleculeLoadingIndicator.visibility = View.GONE
                binding.scanProgress.visibility = View.VISIBLE
                binding.scanStatusText.text = getString(R.string.scanning_text)
            }
            .show()
    }

    private suspend fun loadAndRenderMolecule(cid: Int) {
        try {
            val molecule = PubChemApi.getMolecule(cid)

            if (molecule == null || molecule.atoms.isEmpty()) {
                runOnUiThread {
                    isFetchingMolecule = false
                    binding.moleculeLoadingIndicator.visibility = View.GONE
                    binding.scanProgress.visibility = View.VISIBLE
                    binding.scanStatusText.text = getString(R.string.scanning_text)
                    Toast.makeText(this@ArModelActivity, getString(R.string.molecule_no_3d), Toast.LENGTH_LONG).show()
                }
                return
            }

            runOnUiThread {
                try {
                    val sceneView = binding.arSceneView
                    val engine = sceneView.engine
                    val materialLoader = MaterialLoader(engine, this@ArModelActivity)

                    val moleculeNode = MoleculeRenderer.buildMoleculeNode(
                        engine = engine,
                        materialLoader = materialLoader,
                        molecule = molecule
                    )

                    binding.moleculeLoadingIndicator.visibility = View.GONE
                    pendingMoleculeNode = Pair(moleculeNode, molecule.properties)
                    isFetchingMolecule = false
                    binding.scanStatusText.text = "Ready to place — scanning for surface…"
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to build molecule node", e)
                    isFetchingMolecule = false
                    binding.moleculeLoadingIndicator.visibility = View.GONE
                    binding.scanProgress.visibility = View.VISIBLE
                    binding.scanStatusText.text = getString(R.string.scanning_text)
                    Toast.makeText(this@ArModelActivity, "Error building molecule", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load molecule CID=$cid", e)
            runOnUiThread {
                isFetchingMolecule = false
                binding.moleculeLoadingIndicator.visibility = View.GONE
                binding.scanProgress.visibility = View.VISIBLE
                binding.scanStatusText.text = getString(R.string.scanning_text)
                Toast.makeText(this@ArModelActivity, getString(R.string.molecule_fetch_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun tryPlaceMoleculeNode(frame: Frame, moleculeData: Pair<Node, MoleculeProperties>) {
        if (isModelPlaced) return
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        val session = binding.arSceneView.session ?: return
        val planes = session.getAllTrackables(Plane::class.java)
            .filter { plane -> plane.trackingState == TrackingState.TRACKING }
            .filter { plane -> plane.extentX > 0.1f && plane.extentZ > 0.1f }

        val plane = planes.firstOrNull()

        if (plane != null) {
            Log.d(TAG, "Found plane, placing molecule")
            val anchor = plane.createAnchor(plane.centerPose)
            pendingMoleculeNode = null
            isFetchingMolecule = false
            placeMoleculeOnAnchor(anchor, moleculeData.first, moleculeData.second)
        }
    }

    private fun placeMoleculeOnAnchor(
        anchor: com.google.ar.core.Anchor,
        moleculeNode: Node,
        properties: MoleculeProperties
    ) {
        if (isModelPlaced) return
        isModelPlaced = true
        currentMoleculeProperties = properties
        currentModelNode = moleculeNode

        val sceneView = binding.arSceneView
        val anchorNode = AnchorNode(sceneView.engine, anchor).apply {
            isEditable = true
        }

        sceneView.addChildNode(anchorNode)
        currentAnchorNode = anchorNode
        anchorNode.addChildNode(moleculeNode)

        runOnUiThread {
            val displayName = properties.commonName.ifBlank { properties.iupacName.ifBlank { properties.molecularFormula } }
            onPlacementSuccess(displayName, properties.molecularWeight)
        }
    }

    // ─── 3D Model Placement (existing GLB models) ──────────────────────

    private fun tryPlaceModel(frame: Frame, modelInfo: ModelInfo) {
        if (isModelPlaced) return
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        val session = binding.arSceneView.session ?: return
        val planes = session.getAllTrackables(Plane::class.java)
            .filter { plane -> plane.trackingState == TrackingState.TRACKING }
            .filter { plane -> plane.extentX > 0.1f && plane.extentZ > 0.1f } 

        val plane = planes.firstOrNull()

        if (plane != null) {
            val anchor = plane.createAnchor(plane.centerPose)
            pendingModelInfo = null
            spawnModel(anchor, modelInfo)
        }
    }

    private fun spawnModel(anchor: com.google.ar.core.Anchor, modelInfo: ModelInfo) {
        if (isModelPlaced) return
        isModelPlaced = true
        currentModelInfo = modelInfo

        val sceneView = binding.arSceneView
        val anchorNode = AnchorNode(sceneView.engine, anchor).apply {
            isEditable = true
        }

        sceneView.addChildNode(anchorNode)
        currentAnchorNode = anchorNode

        lifecycleScope.launch {
            try {
                var modelInstance = try {
                    sceneView.modelLoader.loadModelInstance(modelInfo.modelUrl)
                } catch (e: Exception) {
                    null
                }

                if (modelInstance == null) {
                    modelInstance = try {
                        sceneView.modelLoader.loadModelInstance(modelInfo.modelFileName)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (modelInstance != null) {
                    val modelNode = ModelNode(
                        modelInstance = modelInstance,
                        scaleToUnits = modelInfo.scale,
                        centerOrigin = Position(y = -0.5f)
                    ).apply {
                        isEditable = true
                    }
                    anchorNode.addChildNode(modelNode)
                    currentModelNode = modelNode
                    
                    runOnUiThread {
                        onPlacementSuccess(modelInfo.displayName, "")
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@ArModelActivity, "Could not load 3D model", Toast.LENGTH_LONG).show()
                        onPlacementSuccess(modelInfo.displayName, "")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@ArModelActivity, "Error loading model", Toast.LENGTH_LONG).show()
                    onPlacementSuccess(modelInfo.displayName, "")
                }
            }
        }
    }

    // ─── UI Transitions ──────────────────────────────────────────────────

    private fun onPlacementSuccess(title: String, subtitle: String) {
        // Hide scanning states
        binding.scanProgress.visibility = View.GONE
        binding.scanIndicator.visibility = View.GONE
        binding.detectedWordChip.visibility = View.GONE
        
        // Ensure reset button matches styling
        binding.btnReset.visibility = View.VISIBLE
        
        // Show mockup UI panels
        binding.gestureHints.visibility = View.VISIBLE
        binding.infoCard.visibility = View.VISIBLE
        
        // Set Text
        binding.molName.text = title
        binding.molFormula.text = if (subtitle.isNotBlank()) "Molecular weight: $subtitle g/mol" else ""
        binding.molFormula.visibility = if (subtitle.isNotBlank()) View.VISIBLE else View.GONE
        
        updateChipsAndProps()
        
        // Activate default tab
        binding.tabStructure.performClick()
    }

    private fun updateChipsAndProps() {
        val props = currentMoleculeProperties ?: return
        val formula = props.molecularFormula.uppercase()
        val name = props.commonName.lowercase()
        
        // Populate prop cards
        binding.propMW.text = if (props.molecularWeight.isNotBlank()) "${props.molecularWeight} g/mol" else "18.02 g/mol"
        
        // Mock data logic for chips and extra props based on formula/name
        when {
            formula.contains("H2O") || name.contains("water") -> {
                setChips(listOf("Polar" to "blue", "sp³" to "green", "Bent" to "amber"))
                binding.propBondAngle.text = "104.5 °"
                binding.propGeometry.text = "Bent"
                binding.propLonePairs.text = "2"
            }
            formula.contains("CO2") || name.contains("dioxide") -> {
                setChips(listOf("Nonpolar" to "blue", "sp" to "green", "Linear" to "amber"))
                binding.propBondAngle.text = "180 °"
                binding.propGeometry.text = "Linear"
                binding.propLonePairs.text = "0"
            }
            formula.contains("C8H10N4O2") || name.contains("caffeine") -> {
                setChips(listOf("Alkaloid" to "blue", "sp²/sp³" to "green", "Planar" to "amber"))
                binding.propBondAngle.text = "N/A"
                binding.propGeometry.text = "Planar"
                binding.propLonePairs.text = "Many"
            }
            else -> {
                setChips(listOf("Molecule" to "blue"))
                binding.propBondAngle.text = "Variable"
                binding.propGeometry.text = "Complex"
                binding.propLonePairs.text = "-"
            }
        }
    }

    private fun setChips(chips: List<Pair<String, String>>) {
        val chipViews = listOf(binding.chip1, binding.chip2, binding.chip3)
        chipViews.forEach { it.visibility = View.GONE }
        
        chips.take(3).forEachIndexed { i, (label, type) ->
            chipViews[i].apply {
                text = label
                visibility = View.VISIBLE
                background = when(type) {
                    "blue" -> ContextCompat.getDrawable(context, R.drawable.bg_chip_blue)
                    "green" -> ContextCompat.getDrawable(context, R.drawable.bg_chip_green)
                    else -> ContextCompat.getDrawable(context, R.drawable.bg_chip_amber)
                }
                setTextColor(when(type) {
                    "blue" -> ContextCompat.getColor(context, R.color.chip_blue_text)
                    "green" -> ContextCompat.getColor(context, R.color.chip_green_text)
                    else -> ContextCompat.getColor(context, R.color.chip_amber_text)
                })
            }
        }
    }

    private fun resetScene() {
        setMode(false) // Force AR mode for scanning

        // Remove placed model
        currentModelNode?.let { node ->
            node.parent?.removeChildNode(node)
            node.destroy()
        }
        currentAnchorNode?.let { node ->
            binding.arSceneView.removeChildNode(node)
            node.destroy()
        }
        
        currentAnchorNode = null
        currentModelNode = null
        currentModelInfo = null
        currentMoleculeProperties = null
        pendingModelInfo = null
        pendingMoleculeNode = null
        isModelPlaced = false
        isFetchingMolecule = false

        // Hide UI elements
        binding.btnReset.visibility = View.GONE
        binding.detectedWordChip.visibility = View.GONE
        binding.moleculeLoadingIndicator.visibility = View.GONE
        binding.gestureHints.visibility = View.GONE
        binding.infoCard.visibility = View.GONE

        // Restore scanning state
        binding.scanIndicator.visibility = View.VISIBLE
        binding.scanProgress.visibility = View.VISIBLE
        binding.scanStatusText.text = getString(R.string.scanning_text)
    }

    override fun onDestroy() {
        super.onDestroy()
        textRecognitionManager.close()
    }
}
