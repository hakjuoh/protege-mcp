package io.github.hakjuoh.ui;

import org.protege.editor.owl.ui.OWLWorkspaceViewsTab;

/**
 * The dedicated <em>Ontology Assistant</em> workspace tab. A trivial subclass of Protégé's generic OWL views
 * tab whose layout comes from {@code viewconfig-ontologyassistanttab.xml} (declared in {@code plugin.xml}).
 *
 * <p>It exists only so this bundle <strong>references</strong> {@link OWLWorkspaceViewsTab} in its
 * bytecode: that makes the bundle plugin add a real {@code Import-Package} for
 * {@code org.protege.editor.owl.ui}. Naming {@code OWLWorkspaceViewsTab} directly in {@code plugin.xml}
 * (a string) would leave the package un-imported, so the tab would fail to instantiate with a
 * {@code ClassNotFoundException} from this bundle's classloader.
 */
public class ChatTab extends OWLWorkspaceViewsTab {

    private static final long serialVersionUID = 1L;
}
