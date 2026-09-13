/* 上海如静知华信息科技有限公司 https://www.zhuatech.cn/ */
package cn.zhuatech.rma;
import org.springframework.stereotype.Component;
import java.util.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import static cn.zhuatech.rma.Model.*;
import static cn.zhuatech.rma.Engine.*;
@Component public class Domain {
 static String text(Row r,String k){return txt(r.data(),k);}
 static List<Row> linked(Engine e,User u,String module,String field,String id){return e.all(u,module).stream().filter(x->text(x,field).equals(id)).toList();}
 void checkSale(Engine e,User u,Map<String,Object>d,Row self){
  Row sale=e.ref(u,d,"sale","sales"),policy=e.ref(u,sale.data(),"policy","policies");
  LocalDate sold=date(sale.data(),"soldAt"),requested=date(d,"requestedAt");
  require(!requested.isBefore(sold)&&!requested.isAfter(LocalDate.now()),"申请日期须在购买后且不能是未来");
  long days=ChronoUnit.DAYS.between(sold,requested);
  int limit=txt(d,"reason").equals("DEFECT")?num(policy.data(),"warrantyDays").intValueExact():num(policy.data(),"returnDays").intValueExact();
  require(days<=limit,"已超过政策允许的售后期限");
  int used=linked(e,u,"returns","sale",sale.id()).stream().filter(x->self==null||!x.id().equals(self.id())).filter(x->!x.state().equals("REJECTED")).mapToInt(x->num(x.data(),"quantity").intValueExact()).sum();
  require(used+num(d,"quantity").intValueExact()<=num(sale.data(),"quantity").intValueExact(),"申请数量超过可退余量");
 }
 public void create(Engine e,User u,String module,Map<String,Object>d){
  switch(module){
   case "sales" -> {e.ref(u,d,"policy","policies");require(!date(d,"soldAt").isAfter(LocalDate.now()),"不能登记未来销售");require(e.all(u,"sales").stream().noneMatch(x->text(x,"orderNo").equalsIgnoreCase(txt(d,"orderNo"))&&text(x,"sku").equalsIgnoreCase(txt(d,"sku"))),"订单和商品组合已存在");}
   case "returns" -> checkSale(e,u,d,null);
   case "policies" -> require(e.all(u,module).stream().noneMatch(x->text(x,"name").equalsIgnoreCase(txt(d,"name"))),"政策名称已存在");
  }
 }
 public void edit(Engine e,User u,Row r,Map<String,Object>d){
  switch(r.module()){
   case "returns" -> checkSale(e,u,d,r);
   case "sales" -> {require(linked(e,u,"returns","sale",r.id()).isEmpty(),"销售已有售后单，不能修改原始凭证");require(!date(d,"soldAt").isAfter(LocalDate.now()),"不能登记未来销售");require(e.all(u,"sales").stream().noneMatch(x->!x.id().equals(r.id())&&text(x,"orderNo").equalsIgnoreCase(txt(d,"orderNo"))&&text(x,"sku").equalsIgnoreCase(txt(d,"sku"))),"订单和商品组合已存在");}
   case "policies" -> {require(linked(e,u,"sales","policy",r.id()).isEmpty(),"政策已被销售引用，不能修改");require(e.all(u,"policies").stream().noneMatch(x->!x.id().equals(r.id())&&text(x,"name").equalsIgnoreCase(txt(d,"name"))),"政策名称已存在");}
  }
 }
 public String action(Engine e,User u,Row r,String action,Map<String,Object>i,Map<String,Object>d){
  String k=r.module()+"."+action;
  switch(k){
   case "returns.submit" -> checkSale(e,u,d,r);
   case "returns.approve" -> {d.put("approvedBy",u.username());d.put("approvedAt",Instant.now().toString());}
   case "returns.reject" -> {d.put("rejectionReason",txt(i,"reason"));d.put("reviewedBy",u.username());}
   case "returns.receive" -> {
    int requested=num(d,"quantity").intValueExact();
    int received=d.containsKey("receivedQuantity")?num(d,"receivedQuantity").intValueExact():0;
    int incoming=num(i,"receivedQuantity").intValueExact();
    require(received+incoming<=requested,"累计收货数量不能超过申请数量");
    String reference=txt(i,"receiptReference");
    require(e.all(u,"receipts").stream().noneMatch(x->text(x,"receiptReference").equalsIgnoreCase(reference)),"收货凭证号重复");
    Instant now=Instant.now();
    e.ledger(u,"receipts","POSTED",Map.of("return",r.id(),"receiptReference",reference,"quantity",incoming,"receivedBy",u.username(),"receivedAt",now.toString()));
    d.put("receivedQuantity",received+incoming);d.put("receivedAt",now.toString());
    if(received+incoming<requested)return "PARTIAL_RECEIVED";
   }
   case "returns.inspect" -> {
    String result=txt(i,"result");d.put("inspectionResult",result);d.put("inspectionComment",txt(i,"comment"));
    e.ledger(u,"inspections",result,Map.of("return",r.id(),"result",result,"comment",txt(i,"comment"),"inspector",u.username()));
    if(result.equals("FAIL"))return "REJECTED";
   }
   case "returns.settle" -> {
    require(e.all(u,"settlements").stream().noneMatch(x->text(x,"reference").equalsIgnoreCase(txt(i,"reference"))),"处理单号重复");
    Row sale=e.ref(u,d,"sale","sales");BigDecimal amount=money(num(sale.data(),"unitPrice").multiply(num(d,"quantity")));
    Map<String,Object> settlement=new LinkedHashMap<>();settlement.put("return",r.id());settlement.put("reference",txt(i,"reference"));settlement.put("type",txt(d,"requestType"));settlement.put("quantity",num(d,"quantity"));settlement.put("amount",txt(d,"requestType").equals("REFUND")?amount:BigDecimal.ZERO);
    e.ledger(u,"settlements","POSTED",settlement);d.put("settlementReference",txt(i,"reference"));d.put("settlementAmount",settlement.get("amount"));
   }
   case "returns.close" -> require(!linked(e,u,"settlements","return",r.id()).isEmpty(),"缺少退款或换货流水");
  }
  return null;
 }
 public Map<String,Object> metrics(Engine e,User u){return Map.of("待审核退换",e.all(u,"returns").stream().filter(r->r.state().equals("REVIEW")).count(),"待收货退换",e.all(u,"returns").stream().filter(r->Set.of("APPROVED","PARTIAL_RECEIVED").contains(r.state())).count(),"已结案退换",e.all(u,"returns").stream().filter(r->r.state().equals("CLOSED")).count());}
}
