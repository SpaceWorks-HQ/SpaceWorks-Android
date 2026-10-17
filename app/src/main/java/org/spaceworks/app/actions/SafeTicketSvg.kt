package org.spaceworks.app.actions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Tiny QR-only SVG renderer: no browser, images, links, fonts, CSS, entities or remote resources. */
object SafeTicketSvg {
    fun render(bytes:ByteArray):Bitmap {
        require(bytes.isNotEmpty()&&bytes.size<=512*1024)
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(!Regex("<!\\s*(?:DOCTYPE|ENTITY)|<\\?xml-stylesheet",RegexOption.IGNORE_CASE).containsMatchIn(text))
        val factory=XmlPullParserFactory.newInstance().apply {isNamespaceAware=true}
        val parser=factory.newPullParser().apply {setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);setInput(StringReader(text))}
        val shapes=mutableListOf<Pair<android.graphics.Path,Int>>()
        var view:RectF?=null;var nodes=0;var rootClosed=false
        fun number(value:String?)=requireNotNull(value?.toFloatOrNull()?.takeIf {it.isFinite()&&kotlin.math.abs(it)<=1_000_000f})
        fun color(value:String?):Int=when(value?.lowercase()) {null,"black","#000","#000000"->Color.BLACK;"white","#fff","#ffffff"->Color.WHITE;"none"->Color.TRANSPARENT;else->throw IllegalArgumentException("Unsupported QR color")}
        var event=parser.eventType
        while(event!=XmlPullParser.END_DOCUMENT){
            if(event==XmlPullParser.DOCDECL)throw IllegalArgumentException("Unsupported QR document")
            if(event==XmlPullParser.START_TAG){
                require(!rootClosed&&++nodes<=4096&&parser.depth<=3)
                val attrs=(0 until parser.attributeCount).associate {parser.getAttributeName(it) to parser.getAttributeValue(it)}
                when(parser.name){
                    "svg"->{require(parser.depth==1&&view==null);require(attrs.keys.all {it in setOf("width","height","version","viewBox","fill")});val parts=attrs["viewBox"]?.trim()?.split(Regex("[ ,]+"))?.map {number(it)}?:throw IllegalArgumentException("Missing QR view box");require(parts.size==4&&parts[2]>0&&parts[3]>0&&parts[2]/parts[3] in .5f..2f);view=RectF(parts[0],parts[1],parts[0]+parts[2],parts[1]+parts[3])}
                    "g"->{require(view!=null);require(attrs.isEmpty())}
                    "path","rect"->{
                        val box=requireNotNull(view);require(attrs.keys.all {it in if(parser.name=="path")setOf("d","fill","fill-rule","id","fill-opacity","stroke")else setOf("x","y","width","height","fill","id")})
                        require(attrs["stroke"]==null||attrs["stroke"]=="none");require(attrs["fill-opacity"]==null||attrs["fill-opacity"]=="1")
                        val path=if(parser.name=="path"){
                            val data=requireNotNull(attrs["d"]);require(data.length<=500_000&&data.none {it=='\u0000'});requireNotNull(PathParser.createPathFromPathData(data)).apply {if(attrs["fill-rule"]=="evenodd")fillType=android.graphics.Path.FillType.EVEN_ODD else require(attrs["fill-rule"]==null||attrs["fill-rule"]=="nonzero")}
                        }else android.graphics.Path().apply {val x=number(attrs["x"]?:"0");val y=number(attrs["y"]?:"0");val width=number(attrs["width"]);val height=number(attrs["height"]);require(width>=0&&height>=0);addRect(x,y,x+width,y+height,android.graphics.Path.Direction.CW)}
                        val bounds=RectF();path.computeBounds(bounds,true)
                        require(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom).all {it.isFinite()});require(bounds.left>=box.left-box.width()&&bounds.right<=box.right+box.width()&&bounds.top>=box.top-box.height()&&bounds.bottom<=box.bottom+box.height())
                        shapes+=path to color(attrs["fill"])
                    }
                    else->throw IllegalArgumentException("Unsupported QR element")
                }
            }
            if(event==XmlPullParser.END_TAG&&parser.name=="svg")rootClosed=true
            if(event==XmlPullParser.TEXT)require(parser.text.isBlank())
            event=parser.next()
        }
        val box=requireNotNull(view);require(rootClosed&&shapes.isNotEmpty())
        val bitmap=Bitmap.createBitmap(720,720,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val scale=minOf(680/box.width(),680/box.height());canvas.translate((720-box.width()*scale)/2,(720-box.height()*scale)/2);canvas.scale(scale,scale);canvas.translate(-box.left,-box.top)
        val paint=Paint().apply {isAntiAlias=false;style=Paint.Style.FILL}
        shapes.forEach {(path,fill)->if(fill!=Color.TRANSPARENT){paint.color=fill;canvas.drawPath(path,paint)}}
        return bitmap
    }
}
