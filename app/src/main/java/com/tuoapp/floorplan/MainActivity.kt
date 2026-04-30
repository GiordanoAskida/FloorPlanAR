package com.tuoapp.floorplan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.opengl.Matrix
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.tuoapp.floorplan.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

data class RoomPoint(val anchor: Anchor, val label: String)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var arSession: Session? = null
    private val roomPoints = mutableListOf<RoomPoint>()
    private var counter = 0

    companion object { private const val CAM_REQ = 101 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (!hasCam()) reqCam() else initAr()
        binding.btnAddPoint.setOnClickListener { addPoint() }
        binding.btnUndo.setOnClickListener    { undo() }
        binding.btnSave.setOnClickListener    { save() }
        binding.btnClear.setOnClickListener   { clear() }
    }

    override fun onResume() {
        super.onResume()
        if (!hasCam()) return
        try {
            if (arSession == null) createSession()
            arSession?.resume()
            binding.arSurfaceView.resume()
        } catch (e: Exception) { toast("Errore: ${e.message}") }
    }

    override fun onPause() {
        super.onPause()
        binding.arSurfaceView.pause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        roomPoints.forEach { it.anchor.detach() }
        arSession?.close()
    }

    private fun initAr() {
        binding.arSurfaceView.onFrameCallback = { frame -> updateOverlay(frame) }
    }

    private fun createSession() {
        arSession = Session(this).also { s ->
            Config(s).apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                s.configure(this)
            }
            binding.arSurfaceView.session = s
        }
    }

    private fun addPoint() {
        val frame = binding.arSurfaceView.lastFrame.get()
            ?: return toast("Camera non pronta, attendi...")
        if (frame.camera.trackingState != TrackingState.TRACKING)
            return toast("Calibrazione in corso...")
        val w = binding.arSurfaceView.width.toFloat()
        val h = binding.arSurfaceView.height.toFloat()
        val hits = frame.hitTest(w / 2f, h / 2f)
        val hit = hits.firstOrNull { it.trackable is Plane && (it.trackable as Plane).isPoseInPolygon(it.hitPose) }
            ?: hits.firstOrNull { it.trackable is DepthPoint }
            ?: hits.firstOrNull()
            ?: return toast("Nessuna superficie. Punta sul pavimento.")
        roomPoints.add(RoomPoint(hit.createAnchor(), "P${++counter}"))
        updateMeasurements()
        toast("Punto P$counter aggiunto")
    }

    private fun undo() {
        if (roomPoints.isEmpty()) return
        val r = roomPoints.removeLast(); r.anchor.detach()
        updateMeasurements(); toast("${r.label} rimosso")
    }

    private fun clear() {
        roomPoints.forEach { it.anchor.detach() }
        roomPoints.clear(); counter = 0
        binding.tvMeasurements.text = "Punta la camera sul pavimento"
        binding.overlayView.setPoints(emptyList(), emptyList())
    }

    private fun updateMeasurements() {
        if (roomPoints.size < 2) {
            binding.tvMeasurements.text = if (roomPoints.isEmpty()) "Punta la camera sul pavimento" else "Aggiungi almeno 2 punti"
            return
        }
        val n = roomPoints.size
        val sb = StringBuilder("Misure:
")
        var perim = 0f
        for (i in 0 until n) {
            val d = dist(roomPoints[i].anchor.pose, roomPoints[(i+1)%n].anchor.pose)
            perim += d
            sb.append("${roomPoints[i].label}->  ${roomPoints[(i+1)%n].label}: ${"%.2f".format(d)}m
")
        }
        sb.append("
Perimetro: ${"%.2f".format(perim)}m")
        if (n >= 3) sb.append("
Area: ${"%.2f".format(area())}m2")
        binding.tvMeasurements.text = sb.toString()
    }

    private fun dist(a: Pose, b: Pose) = sqrt((b.tx()-a.tx()).pow(2) + (b.tz()-a.tz()).pow(2))

    private fun area(): Float {
        var a = 0f; val n = roomPoints.size
        for (i in 0 until n) {
            val j = (i+1)%n
            a += roomPoints[i].anchor.pose.tx() * roomPoints[j].anchor.pose.tz()
            a -= roomPoints[j].anchor.pose.tx() * roomPoints[i].anchor.pose.tz()
        }
        return abs(a) / 2f
    }

    private fun updateOverlay(frame: Frame) {
        val w = binding.arSurfaceView.width
        val h = binding.arSurfaceView.height
        if (w == 0 || h == 0 || roomPoints.isEmpty()) {
            binding.overlayView.setPoints(emptyList(), emptyList()); return
        }
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        val vm = FloatArray(16); val pm = FloatArray(16)
        frame.camera.getViewMatrix(vm, 0)
        frame.camera.getProjectionMatrix(pm, 0, 0.01f, 100f)
        val pts = roomPoints.mapNotNull { rp ->
            proj(rp.anchor.pose, vm, pm, w, h)?.let { Triple(it.first, it.second, rp.label) }
        }
        val dists = mutableListOf<Pair<PointF, String>>()
        val n = roomPoints.size
        if (n >= 2) for (i in 0 until n) {
            val p1 = roomPoints[i].anchor.pose
            val p2 = roomPoints[(i+1)%n].anchor.pose
            val mid = Pose(floatArrayOf((p1.tx()+p2.tx())/2f, p1.ty(), (p1.tz()+p2.tz())/2f), p1.rotationQuaternion)
            proj(mid, vm, pm, w, h)?.let { val dm = "%.2f".format(dist(p1,p2)) dists.add(PointF(it.first, it.second) to "${dm}m") }
        }
        binding.overlayView.setPoints(pts, dists)
    }

    private fun proj(pose: Pose, vm: FloatArray, pm: FloatArray, w: Int, h: Int): Pair<Float,Float>? {
        val mm = FloatArray(16); pose.toMatrix(mm, 0)
        val mv = FloatArray(16); Matrix.multiplyMM(mv, 0, vm, 0, mm, 0)
        val mvp = FloatArray(16); Matrix.multiplyMM(mvp, 0, pm, 0, mv, 0)
        val clip = FloatArray(4); Matrix.multiplyMV(clip, 0, mvp, 0, floatArrayOf(0f,0f,0f,1f), 0)
        if (clip[3] <= 0f) return null
        val nx = clip[0]/clip[3]; val ny = clip[1]/clip[3]
        if (nx !in -1f..1f || ny !in -1f..1f) return null
        return Pair((nx+1f)/2f*w, (1f-ny)/2f*h)
    }

    private fun save() {
        if (roomPoints.size < 3) { toast("Aggiungi almeno 3 punti"); return }
        val sz = 1024; val mg = 80f
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); cv.drawColor(Color.WHITE)
        val wall  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1565C0"); strokeWidth = 5f; style = Paint.Style.STROKE }
        val fill  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E3F2FD"); style = Paint.Style.FILL }
        val dot   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D32F2F"); style = Paint.Style.FILL }
        val lbl   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D32F2F"); textSize = 32f; typeface = Typeface.DEFAULT_BOLD }
        val dim   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2E7D32"); textSize = 26f }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 36f; typeface = Typeface.DEFAULT_BOLD }
        val xs = roomPoints.map { it.anchor.pose.tx() }
        val zs = roomPoints.map { it.anchor.pose.tz() }
        val minX = xs.min(); val maxX = xs.max(); val minZ = zs.min(); val maxZ = zs.max()
        val da = sz - mg * 2
        val scale = minOf(da / (maxX-minX).coerceAtLeast(0.001f), da / (maxZ-minZ).coerceAtLeast(0.001f))
        fun px(x: Float, z: Float) = PointF(mg+(x-minX)*scale+(da-(maxX-minX)*scale)/2f, mg+(z-minZ)*scale+(da-(maxZ-minZ)*scale)/2f)
        val path = Path(); val fp = px(roomPoints[0].anchor.pose.tx(), roomPoints[0].anchor.pose.tz())
        path.moveTo(fp.x, fp.y)
        for (i in 1 until roomPoints.size) { val p = px(roomPoints[i].anchor.pose.tx(), roomPoints[i].anchor.pose.tz()); path.lineTo(p.x, p.y) }
        path.close(); cv.drawPath(path, fill); cv.drawPath(path, wall)
        val n = roomPoints.size
        for (i in 0 until n) {
            val p1 = roomPoints[i].anchor.pose; val p2 = roomPoints[(i+1)%n].anchor.pose
            val pp1 = px(p1.tx(), p1.tz()); val pp2 = px(p2.tx(), p2.tz())
            cv.drawCircle(pp1.x, pp1.y, 10f, dot)
            cv.drawText(roomPoints[i].label, pp1.x+14f, pp1.y-14f, lbl)
            val dt = "${"%.2f".format(dist(p1,p2))}m"
            cv.drawText(dt, (pp1.x+pp2.x)/2f - dim.measureText(dt)/2f, (pp1.y+pp2.y)/2f - 8f, dim)
        }
        var tp = 0f; for (i in 0 until n) tp += dist(roomPoints[i].anchor.pose, roomPoints[(i+1)%n].anchor.pose)
        cv.drawText("FloorPlan AR", mg, mg-20f, title)
        cv.drawText("Area: ${"%.2f".format(area())}m2  Perim: ${"%.2f".format(tp)}m", mg, sz-20f, dim.apply { textSize=22f; color=Color.DKGRAY })
        val f = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "planimetria_${System.currentTimeMillis()}.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
        toast("Salvata: ${f.absolutePath}")
    }

    private fun hasCam() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun reqCam() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAM_REQ)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == CAM_REQ && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) { initAr(); onResume() }
        else toast("Permesso fotocamera necessario")
    }
}
