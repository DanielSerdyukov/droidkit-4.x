package droidkit.inject.internal;

import com.squareup.javawriter.JavaWriter;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;

/**
 * @author Daniel Serdyukov
 */
class ActivityMaker extends ProxyMaker {

    ActivityMaker(JavacTools tools, Element classElement) {
        super(tools, classElement);
        emitImports(
                "android.view.View",
                "android.os.Bundle",
                "android.support.v4.util.ArrayMap",
                "droidkit.view.Views",
                "java.util.Map"
        );
        emitField(EnumSet.of(Modifier.PRIVATE, Modifier.FINAL), "ArrayMap<View, View.OnClickListener>", "mOnClick",
                "new ArrayMap<View, View.OnClickListener>()");
    }

    @Override
    protected void brewPublicMethods(JavaWriter writer) throws IOException {
        brewSetContentView(writer);
    }

    @Override
    protected void brewProtectedMethods(JavaWriter writer) throws IOException {
        brewOnPostCreate(writer);
        brewOnResume(writer);
        brewOnPause(writer);
        brewOnDestroy(writer);
    }

    @Override
    protected void brewPrivateMethods(JavaWriter writer) throws IOException {
        brewFindViews(writer);
        brewOnClickEmitters(writer);
    }

    private void brewSetContentView(JavaWriter writer) throws IOException {
        writer.emitAnnotation(Override.class);
        writer.beginMethod("void", "setContentView", EnumSet.of(Modifier.PUBLIC), "int", "layoutResId");
        writer.emitStatement("super.setContentView(layoutResId)");
        writer.emitStatement("findViews()");
        writer.endMethod();
        writer.emitEmptyLine();
        writer.emitAnnotation(Override.class);
        writer.beginMethod("void", "setContentView", EnumSet.of(Modifier.PUBLIC), "View", "view");
        writer.emitStatement("super.setContentView(view)");
        writer.emitStatement("findViews()");
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void brewOnPostCreate(JavaWriter writer) throws IOException {
        writer.emitAnnotation(Override.class);
        writer.beginMethod("void", "onPostCreate", EnumSet.of(Modifier.PROTECTED), "Bundle", "savedInstanceState");
        writer.emitStatement("super.onPostCreate(savedInstanceState)");
        for (final Map.Entry<ExecutableElement, int[]> entry : mOnClick.entrySet()) {
            for (final int viewId : entry.getValue()) {
                writer.emitStatement("emitOnClick%d()", viewId);
            }
        }
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void brewOnResume(JavaWriter writer) throws IOException {
        writer.emitAnnotation(Override.class);
        writer.beginMethod("void", "onResume", EnumSet.of(Modifier.PROTECTED));
        writer.emitStatement("super.onResume()");
        writer.beginControlFlow("for(final Map.Entry<View, View.OnClickListener> entry : mOnClick.entrySet())");
        writer.emitStatement("entry.getKey().setOnClickListener(entry.getValue())");
        writer.endControlFlow();
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void brewOnPause(JavaWriter writer) throws IOException {
        writer.emitAnnotation(Override.class);
        writer.beginMethod("void", "onPause", EnumSet.of(Modifier.PROTECTED));
        writer.beginControlFlow("for(final View view : mOnClick.keySet())");
        writer.emitStatement("view.setOnClickListener(null)");
        writer.endControlFlow();
        writer.emitStatement("super.onPause()");
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void brewOnDestroy(JavaWriter writer) throws IOException {
        writer.emitAnnotation(Override.class);
        writer.beginMethod("void", "onDestroy", EnumSet.of(Modifier.PROTECTED));
        writer.emitStatement("mOnClick.clear()");
        writer.emitStatement("super.onDestroy()");
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void brewFindViews(JavaWriter writer) throws IOException {
        writer.beginMethod("void", "findViews", EnumSet.of(Modifier.PRIVATE));
        for (final Map.Entry<VariableElement, int[]> entry : mInjectView.entrySet()) {
            final int[] viewIds = entry.getValue();
            if (viewIds.length > 0) {
                writer.emitStatement("%s.%s = Views.findById(this, %d)",
                        M_DELEGATE, entry.getKey().getSimpleName(), viewIds[0]);
            }
        }
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void brewOnClickEmitters(JavaWriter writer) throws IOException {
        for (final Map.Entry<ExecutableElement, int[]> entry : mOnClick.entrySet()) {
            final ExecutableElement method = entry.getKey();
            final List<? extends VariableElement> parameters = method.getParameters();
            for (final int viewId : entry.getValue()) {
                writer.beginMethod("void", "emitOnClick" + viewId, EnumSet.of(Modifier.PRIVATE));
                writer.emitStatement("final View view = Views.findById(this, %d)", viewId);
                writer.beginControlFlow("if(view != null)");
                writer.beginControlFlow("mOnClick.put(view, new View.OnClickListener()");
                writer.emitAnnotation(Override.class);
                writer.beginMethod("void", "onClick", EnumSet.of(Modifier.PUBLIC), "View", "v");
                if (!parameters.isEmpty() && mTools.isSubtype(parameters.get(0), "android.view.View")) {
                    writer.emitStatement("%s.%s(v)", M_DELEGATE, entry.getKey().getSimpleName());
                } else {
                    writer.emitStatement("%s.%s()", M_DELEGATE, entry.getKey().getSimpleName());
                }
                writer.endMethod();
                writer.endControlFlow(")");
                writer.endControlFlow();
                writer.endMethod();
                writer.emitEmptyLine();
            }
        }
    }

}
