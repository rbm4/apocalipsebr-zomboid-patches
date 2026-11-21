import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.tools.*;

/** Report-only AST member delta inventory. No attribution or game-class loading. */
public final class NativeDeltaScan {
    record Member(String source,long line,String owner,String name,String kind,String key,String hash) { }
    static final Map<String,Member> before=new TreeMap<>(),after=new TreeMap<>();
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath().normalize(), list=Path.of(args[1]), out=Path.of(args[2]);
        var compiler=ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(null,Locale.ROOT,StandardCharsets.UTF_8)) {
            var paths=new ArrayList<Path>();
            for(String relative:Files.readAllLines(list)) {
                paths.add(root.resolve("42.22.2/decompiled").resolve(relative));
                paths.add(root.resolve("42.22.2/src").resolve(relative));
            }
            var task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,manager.getJavaFileObjectsFromPaths(paths));
            var trees=Trees.instance(task);
            for(var unit:task.parse()) {
                String source=root.relativize(Path.of(unit.getSourceFile().toUri())).toString().replace('\\','/');
                Map<String,Member> target=source.contains("/decompiled/")?before:after;
                new TreeScanner<Void,String>() {
                    @Override public Void visitClass(ClassTree node,String owner) {
                        if(node.getSimpleName().toString().isEmpty())return null;
                        String current=owner==null?unit.getPackageName()+"."+node.getSimpleName():owner+"."+node.getSimpleName();
                        for(Tree member:node.getMembers()) {
                            String kind,name,key;
                            if(member instanceof MethodTree method) {
                                kind="METHOD";name=method.getName().toString();
                                key=current+"#"+name+"("+String.join(",",method.getParameters().stream().map(p->p.getType().toString()).toList())+")";
                            } else if(member instanceof VariableTree field) {kind="FIELD";name=field.getName().toString();key=current+"#"+name;}
                            else {scan(member,current);continue;}
                            long position=trees.getSourcePositions().getStartPosition(unit,member);
                            target.put(key,new Member(source,unit.getLineMap().getLineNumber(position),current,name,kind,key,hash(member.toString())));
                        }
                        return null;
                    }
                }.scan(unit,null);
            }
        }
        var keys=new TreeSet<>(before.keySet());keys.addAll(after.keySet());
        try(var writer=Files.newBufferedWriter(out)) {
            writer.write("owner,name,kind,signature,change,vanillaSource,vanillaLine,patchedSource,patchedLine\n");
            for(String key:keys) {
                Member old=before.get(key),next=after.get(key),member=next==null?old:next;
                if(old!=null&&next!=null&&old.hash().equals(next.hash()))continue;
                String[] row={member.owner(),member.name(),member.kind(),key,old==null?"added":next==null?"removed":"changed",
                    old==null?"":old.source(),old==null?"":Long.toString(old.line()),next==null?"":next.source(),next==null?"":Long.toString(next.line())};
                for(int i=0;i<row.length;i++){if(i>0)writer.write(',');writer.write('"'+row[i].replace("\"","\"\"")+'"');}writer.write('\n');
            }
        }
    }
    static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
}
