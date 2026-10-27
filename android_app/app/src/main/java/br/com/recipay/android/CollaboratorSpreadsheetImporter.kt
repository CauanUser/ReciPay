package br.com.recipay.android
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
data class ImportedCollaborator(val name:String,val team:String,val pixKey:String,val pixType:String,val pixHolder:String,val role:String,val errors:List<String>)
data class CollaboratorSpreadsheetPreview(val fileName:String,val rows:List<ImportedCollaborator>)
object CollaboratorSpreadsheetImporter {
 fun read(input:InputStream,fileName:String):CollaboratorSpreadsheetPreview {
  val entries=linkedMapOf<String,ByteArray>()
  ZipInputStream(input).use { zip -> while(true) { val e=zip.nextEntry?:break; if(!e.isDirectory) entries[e.name]=zip.readBytes(); zip.closeEntry() } }
  fun xml(path:String):org.w3c.dom.Document { val bytes=entries[path]?:error("Arquivo XLSX inválido: "+path+" ausente."); val f=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}; try{f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}catch(_:Exception){};try{f.setFeature("http://xml.org/sax/features/external-general-entities",false)}catch(_:Exception){};try{f.setFeature("http://xml.org/sax/features/external-parameter-entities",false)}catch(_:Exception){};return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)) }
  fun txt(e:Element):String{val ns=e.getElementsByTagNameNS("*","t");val b=StringBuilder();for(i in 0 until ns.length)b.append(ns.item(i).textContent);return b.toString()}
  fun child(e:Element,n:String):String{val ns=e.getElementsByTagNameNS("*",n);return if(ns.length==0)"" else ns.item(0).textContent.orEmpty()}
  fun norm(v:String)=Normalizer.normalize(v.trim().lowercase(),Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"),"").replace(Regex("[^a-z0-9]+"),"_").trim('_')
  fun col(ref:String):Int{var n=0;for(c in ref.takeWhile{it.isLetter()})n=n*26+c.uppercaseChar().code-64;return n-1}
  val wb=xml("xl/workbook.xml");val rels=xml("xl/_rels/workbook.xml.rels");val targets=mutableMapOf<String,String>();val rn=rels.getElementsByTagNameNS("*","Relationship");for(i in 0 until rn.length){val e=rn.item(i) as Element;targets[e.getAttribute("Id")]=e.getAttribute("Target")}
  val shared=mutableListOf<String>();entries["xl/sharedStrings.xml"]?.let{bytes->val ns=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).getElementsByTagNameNS("*","si");for(i in 0 until ns.length)shared+=txt(ns.item(i) as Element)}
  val out=mutableListOf<ImportedCollaborator>();var found=false;val sheets=wb.getElementsByTagNameNS("*","sheet")
  for(si in 0 until sheets.length){val sh=sheets.item(si) as Element;if(norm(sh.getAttribute("name"))!="colaboradores")continue;found=true;val rid=sh.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");val target=targets[rid]?:continue;val path=if(target.startsWith("/"))target.removePrefix("/")else if(target.startsWith("xl/"))target else "xl/"+target;val rowNodes=xml(path).getElementsByTagNameNS("*","row");val rows=mutableListOf<Map<Int,String>>()
   for(ri in 0 until rowNodes.length){val row=rowNodes.item(ri) as Element;val cells=linkedMapOf<Int,String>();var ch=row.firstChild;while(ch!=null){if(ch.nodeType==Node.ELEMENT_NODE&&(ch as Element).localName=="c"){val ce=ch as Element;val v=when(ce.getAttribute("t")){"inlineStr"->txt(ce);"s"->shared.getOrNull(child(ce,"v").toIntOrNull()?:-1).orEmpty();else->child(ce,"v")};cells[col(ce.getAttribute("r"))]=v.trim()};ch=ch.nextSibling};rows+=cells}
   val hi=rows.indexOfFirst{r->val h=r.values.map{norm(it)};h.any{it in setOf("nome","colaborador","name")}&&h.any{it in setOf("chave_pix","chavepix","pix_key","chave")}};if(hi<0)error("A aba Colaboradores precisa conter as colunas nome e chave_pix.");val headers=rows[hi].mapValues{norm(it.value)}
   fun find(vararg names:String):Int?=headers.entries.firstOrNull{(_,v)->names.any{norm(it)==v}}?.key
   val nc=find("nome","colaborador","name")?:error("Coluna nome não encontrada.");val kc=find("chave_pix","chave pix","chave","pix_key")?:error("Coluna chave_pix não encontrada.");val ec=find("equipe","time","team");val tc=find("tipo_chave","tipo chave","tipo de chave","pix_type");val hc=find("titular_pix","titular pix","titular","pix_holder");val rc=find("cargo","funcao","função","role")
   for(i in hi+1 until rows.size){val r=rows[i];if(r.values.all{it.isBlank()})continue;val name=r[nc].orEmpty().trim();val key=r[kc].orEmpty().trim();val errors=mutableListOf<String>();if(name.isBlank())errors+="nome vazio";if(key.isBlank())errors+="chave Pix vazia";out+=ImportedCollaborator(name,ec?.let{r[it].orEmpty()}.orEmpty(),key,tc?.let{r[it].orEmpty()}.orEmpty(),hc?.let{r[it].orEmpty()}.orEmpty().ifBlank{name},rc?.let{r[it].orEmpty()}.orEmpty(),errors)}
  }
  if(!found)error("Não encontrei a aba Colaboradores. Use a planilha modelo do ReciPay.");if(out.isEmpty())error("Nenhum colaborador foi encontrado na planilha.");return CollaboratorSpreadsheetPreview(fileName,out)
 }
}