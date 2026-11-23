import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.tools.*;

/** Report-only compiler-symbol census. Reads source/JARs and writes CSV/diagnostics; never runs the game. */
public final class NativeDependencyScan {
    static final List<String[]> rows=new ArrayList<>(), dynamic=new ArrayList<>();
    static Trees trees;
    static Path root;
    static Set<String> custom;

    public static void main(String[] args) throws Exception {
        root=Path.of(args[0]).toAbsolutePath().normalize();
        Path inputs=Path.of(args[1]), output=Path.of(args[2]);
        String classpath=args[3];
        var paths=Files.readAllLines(inputs).stream().filter(s -> !s.isBlank()).map(Path::of).toList();
        custom=new HashSet<>();
        for(Path path:paths) custom.add(root.resolve("42.22.2/src").relativize(path).toString().replace('\\','/').replace('/','.').replaceAll("\\.java$",""));
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        Files.createDirectories(output);
        try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)) {
            var task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-implicit:none",
                "-encoding","UTF-8","-classpath",classpath,"-sourcepath",output.resolve("empty-sourcepath").toString()),
                null,manager.getJavaFileObjectsFromPaths(paths));
            var units=new ArrayList<CompilationUnitTree>();task.parse().forEach(units::add);
            task.analyze();trees=Trees.instance(task);
            for(var unit:units) new Scanner(unit).scan(unit,null);
        }
        write(output.resolve("native-api.csv"),List.of("source","line","kind","owner","member","access","vanillaSource"),rows);
        write(output.resolve("dynamic-and-config.csv"),List.of("source","line","kind","expression"),dynamic);
        try(var writer=Files.newBufferedWriter(output.resolve("compiler-diagnostics.txt"))) {
            for(var diagnostic:diagnostics.getDiagnostics()) {
                String source=diagnostic.getSource()==null?"":relative(Path.of(diagnostic.getSource().toUri()));
                writer.write(source+":"+diagnostic.getLineNumber()+": "+diagnostic.getKind()+": "+diagnostic.getMessage(Locale.ROOT)+"\n");
            }
        }
        System.out.println("native API reference rows="+rows.size()+"; dynamic/config rows="+dynamic.size()
            +"; compiler errors="+diagnostics.getDiagnostics().stream().filter(d -> d.getKind()==Diagnostic.Kind.ERROR).count());
    }

    static String relative(Path path) {return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\','/');}
    static TypeElement owner(Element e) {
        for(Element p=e;p!=null;p=p.getEnclosingElement()) if(p instanceof TypeElement t)return t;
        return null;
    }
    static TypeElement top(TypeElement element) {
        while(element.getEnclosingElement() instanceof TypeElement t)element=t;
        return element;
    }
    static void write(Path path,List<String> headers,List<String[]> values) throws IOException {
        try(var writer=Files.newBufferedWriter(path)) {
            writer.write(String.join(",",headers)+"\n");
            values.sort(Comparator.comparing((String[] row)->row[0]).thenComparingLong(row->Long.parseLong(row[1])).thenComparing(row->String.join("|",row)));
            Set<String> seen=new HashSet<>();
            for(String[] row:values) {
                String key=String.join("\u0000",row);if(!seen.add(key))continue;
                for(int i=0;i<row.length;i++) {if(i>0)writer.write(',');writer.write('"'+row[i].replace("\"","\"\"")+'"');}
                writer.write('\n');
            }
        }
    }

    static final class Scanner extends TreePathScanner<Void,Void> {
        final CompilationUnitTree unit;
        final String source;
        Scanner(CompilationUnitTree unit) {this.unit=unit;source=relative(Path.of(unit.getSourceFile().toUri()));}
        String line(Tree tree) {return Long.toString(unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit,tree)));}
        void dynamic(String kind,Tree tree) {
            String expression=tree.toString();if(expression.length()>1200)expression=expression.substring(0,1200)+"...";
            dynamic.add(new String[]{source,line(tree),kind,expression});
        }
        void record(String kind,Tree tree,Element element) {
            if(element==null)return;
            TypeElement owner=owner(element);if(owner==null)return;
            String top=top(owner).getQualifiedName().toString();
            if(!top.startsWith("zombie.")||custom.contains(top)||owner.asType().getKind()==TypeKind.ERROR)return;
            String vanilla="42.22.2/decompiled/"+top.replace('.','/')+".java";
            if(!Files.isRegularFile(root.resolve(vanilla)))return;
            rows.add(new String[]{source,line(tree),kind,owner.getQualifiedName().toString(),element.toString(),element.getModifiers().toString(),vanilla});
        }
        @Override public Void visitMethodInvocation(MethodInvocationTree node,Void unused) {
            Element element=trees.getElement(getCurrentPath());
            if(element instanceof ExecutableElement)record("CALL",node,element);
            else dynamic("UNRESOLVED_CALL",node);
            String name=node.getMethodSelect().toString();
            if(name.matches(".*\\.(forName|getDeclaredField|getDeclaredFields|getField|getFields|getDeclaredMethod|getDeclaredMethods|getMethod|getMethods|getDeclaredConstructor|getDeclaredConstructors|getConstructor|getConstructors|setAccessible|trySetAccessible)$"))dynamic("REFLECTION",node);
            if(name.matches(".*\\.(getProperty|getenv|getInteger|getLong|getBoolean)$"))dynamic("CONFIG_CALL",node);
            return super.visitMethodInvocation(node,unused);
        }
        @Override public Void visitNewClass(NewClassTree node,Void unused) {
            record("CONSTRUCT",node,trees.getElement(getCurrentPath()));return super.visitNewClass(node,unused);
        }
        @Override public Void visitMemberSelect(MemberSelectTree node,Void unused) {
            fieldOrType(node);return super.visitMemberSelect(node,unused);
        }
        @Override public Void visitIdentifier(IdentifierTree node,Void unused) {
            fieldOrType(node);return super.visitIdentifier(node,unused);
        }
        void fieldOrType(Tree node) {
            Element e=trees.getElement(getCurrentPath());
            if(e instanceof VariableElement&&(e.getKind()==ElementKind.FIELD||e.getKind()==ElementKind.ENUM_CONSTANT)) {
                Tree parent=getCurrentPath().getParentPath()==null?null:getCurrentPath().getParentPath().getLeaf();
                boolean write=parent instanceof AssignmentTree assignment&&assignment.getVariable()==node
                    ||parent instanceof CompoundAssignmentTree||parent instanceof UnaryTree unary&&Set.of(Tree.Kind.POSTFIX_INCREMENT,Tree.Kind.POSTFIX_DECREMENT,Tree.Kind.PREFIX_INCREMENT,Tree.Kind.PREFIX_DECREMENT).contains(unary.getKind());
                record(write?"FIELD_WRITE":"FIELD_READ",node,e);
            } else if(e instanceof TypeElement) record("TYPE",node,e);
        }
        @Override public Void visitLiteral(LiteralTree node,Void unused) {
            if(node.getValue() instanceof String text) {
                if(text.startsWith("zombie."))dynamic("NATIVE_NAME_STRING",node);
                if(text.startsWith("media/"))dynamic("RESOURCE_STRING",node);
                if(text.startsWith("apocbr.")||text.startsWith("APOCBR_"))dynamic("CONFIG_STRING",node);
            }
            return super.visitLiteral(node,unused);
        }
    }
}
