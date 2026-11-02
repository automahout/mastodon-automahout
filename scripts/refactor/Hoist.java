import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.*;
import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Finds, in one method, every local variable declared at the method's top level or directly in the body
 * of one labelled loop (plus the loop variable and the parameters), and every identifier that resolves
 * to one of them. Prints TSV: kind (PARAM|DECL|FORVAR|USE), name, type, start, end (char offsets),
 * hasInit, declStart, declEnd (for DECL: the whole statement), isFinal.
 * usage: java Hoist.java <file> <method> <loopLabel> <classpath> <sourcepath>
 */
public class Hoist {
  public static void main(String[] a) throws Exception {
    String file = a[0], method = a[1], label = a[2];
    JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager fm = jc.getStandardFileManager(null, null, null);
    Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(file);
    JavacTask task = (JavacTask) jc.getTask(null, fm, d -> { if (d.getKind()==Diagnostic.Kind.ERROR) System.err.println(d); },
        List.of("-proc:none", "-cp", a[3], "-sourcepath", a[4], "-implicit:none", "-Xlint:none", "-nowarn"), null, units);
    CompilationUnitTree cu = task.parse().iterator().next();
    task.analyze();
    Trees trees = Trees.instance(task);
    SourcePositions sp = trees.getSourcePositions();
    Map<Element, String> targets = new LinkedHashMap<>();
    List<String> out = new ArrayList<>();
    new TreePathScanner<Void, Void>() {
      @Override public Void visitMethod(MethodTree m, Void v) {
        if (!m.getName().contentEquals(method) || m.getBody() == null) return super.visitMethod(m, v);
        for (VariableTree p : m.getParameters()) {
          Element e = trees.getElement(new TreePath(new TreePath(getCurrentPath(), m), p));
          targets.put(e, "PARAM");
          out.add(String.join("\t", "PARAM", p.getName().toString(), e.asType().toString(),
              ""+sp.getStartPosition(cu, p), ""+sp.getEndPosition(cu, p), "0", "", "", "1"));
        }
        TreePath bodyPath = new TreePath(new TreePath(getCurrentPath(), m), m.getBody());
        collectDecls(m.getBody(), bodyPath, null);
        for (StatementTree s : m.getBody().getStatements())
          if (s instanceof LabeledStatementTree ls && ls.getLabel().contentEquals(label)
              && ls.getStatement() instanceof ForLoopTree f) {
            TreePath lp = new TreePath(new TreePath(bodyPath, ls), f);
            for (StatementTree init : f.getInitializer())
              if (init instanceof VariableTree vt) {
                Element e = trees.getElement(new TreePath(lp, vt));
                targets.put(e, "FORVAR");
                out.add(String.join("\t", "FORVAR", vt.getName().toString(), e.asType().toString(),
                    ""+sp.getStartPosition(cu, vt), ""+sp.getEndPosition(cu, vt), "1", "", "", "0"));
              }
            if (f.getStatement() instanceof BlockTree b)
              collectDecls(b, new TreePath(lp, b), "LOOP");
          }
        // uses
        new TreePathScanner<Void, Void>() {
          @Override public Void visitIdentifier(IdentifierTree id, Void v2) {
            Element e = trees.getElement(getCurrentPath());
            if (e != null && targets.containsKey(e))
              out.add(String.join("\t", "USE", id.getName().toString(), "", ""+sp.getStartPosition(cu, id),
                  ""+sp.getEndPosition(cu, id), "", "", "", ""));
            return super.visitIdentifier(id, v2);
          }
        }.scan(bodyPath, null);
        return null;
      }
      void collectDecls(BlockTree b, TreePath bp, String where) {
        for (StatementTree s : b.getStatements())
          if (s instanceof VariableTree vt) {
            Element e = trees.getElement(new TreePath(bp, vt));
            targets.put(e, "DECL");
            long vs = sp.getStartPosition(cu, vt), ve = sp.getEndPosition(cu, vt);
            boolean fin = vt.getModifiers().getFlags().contains(Modifier.FINAL);
            long ns = -1;
            // start of the initializer, if any
            long is = vt.getInitializer() == null ? -1 : sp.getStartPosition(cu, vt.getInitializer());
            out.add(String.join("\t", "DECL", vt.getName().toString(), e.asType().toString(),
                ""+vs, ""+ve, vt.getInitializer()==null ? "0" : "1", ""+is, where == null ? "TOP" : where, fin ? "1" : "0"));
          }
      }
    }.scan(cu, null);
    for (String s : out) System.out.println(s);
  }
}
