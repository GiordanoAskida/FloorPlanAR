package com.tuoapp.floorplan

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class FloorPlanOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var pts:   List<Triple<Float,Float,String>> = emptyList()
    private var dists: List<Pair<PointF,String>>        = emptyList()
    private val line  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.parseColor("#FF4FC3F7"); strokeWidth=4f; style=Paint.Style.STROKE; pathEffect=DashPathEffect(floatArrayOf(20f,10f),0f) }
    private val dot   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.parseColor("#FFEF5350"); style=Paint.Style.FILL }
    private val bdr   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; strokeWidth=3f; style=Paint.Style.STROKE }
    private val lbl   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=36f; typeface=Typeface.DEFAULT_BOLD }
    private val shd   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.parseColor("#AA000000"); textSize=36f; typeface=Typeface.DEFAULT_BOLD }
    private val dst   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.parseColor("#FFFFF176"); textSize=28f; typeface=Typeface.DEFAULT_BOLD }
    private val dsh   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.parseColor("#AA000000"); textSize=28f; typeface=Typeface.DEFAULT_BOLD }
    fun setPoints(p: List<Triple<Float,Float,String>>, d: List<Pair<PointF,String>>) { pts=p; dists=d; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pts.isEmpty()) return
        if (pts.size >= 2) for (i in pts.indices) { val (x1,y1,_)=pts[i]; val (x2,y2,_)=pts[(i+1)%pts.size]; canvas.drawLine(x1,y1,x2,y2,line) }
        for ((pos,label) in dists) { val tw=dst.measureText(label); canvas.drawText(label,pos.x-tw/2f+2f,pos.y+2f,dsh); canvas.drawText(label,pos.x-tw/2f,pos.y,dst) }
        for ((x,y,label) in pts) { canvas.drawCircle(x,y,18f,dot); canvas.drawCircle(x,y,18f,bdr); canvas.drawText(label,x+24f+2f,y-24f+2f,shd); canvas.drawText(label,x+24f,y-24f,lbl) }
    }
}