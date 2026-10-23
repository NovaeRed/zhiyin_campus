import java.sql.*;
import java.nio.file.*;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
/** Restricted to this run's unique UI account; never modifies existing users. */
class PortalUiUser {
 public static void main(String[] args) throws Exception {
  long id=Long.parseLong(args[0]); String student=args[1]; String role=args[2];
  if (!student.matches("portal_ui_[0-9]+") || !(role.equals("student") || role.equals("admin"))) throw new IllegalArgumentException("Invalid UI fixture");
  Map<?,?> local;
  try(var input=Files.newInputStream(Path.of(args[3],"application-local.yaml"))) { local=new Yaml().load(input); }
  var ds=(Map<?,?>)((Map<?,?>)local.get("spring")).get("datasource");
  String url=value("DB_URL",ds.get("url")), user=value("DB_USERNAME",ds.get("username")), password=value("DB_PASSWORD",ds.get("password"));
  try(var c=DriverManager.getConnection(url,user,password);
      var p=c.prepareStatement("UPDATE users SET role=?,updated_at=NOW() WHERE id=? AND student_no=?")) {
   p.setString(1,role);p.setLong(2,id);p.setString(3,student);if(p.executeUpdate()!=1) throw new IllegalStateException("Fixture account missing");
  }
 }
 static String value(String name,Object fallback) { String value=System.getenv(name);return value!=null&&!value.isBlank()?value:String.valueOf(fallback); }
}
