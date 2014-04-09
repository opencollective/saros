package de.fu_berlin.inf.dpp;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({

de.fu_berlin.inf.dpp.activities.business.TestSuite.class,

de.fu_berlin.inf.dpp.communication.extensions.TestSuite.class,

de.fu_berlin.inf.dpp.concurrent.TestSuite.class,

de.fu_berlin.inf.dpp.concurrent.jupiter.test.puzzles.TestSuite.class,

de.fu_berlin.inf.dpp.editor.colorstorage.TestSuite.class,

de.fu_berlin.inf.dpp.filesystem.TestSuite.class,

de.fu_berlin.inf.dpp.net.TestSuite.class,

de.fu_berlin.inf.dpp.net.internal.TestSuite.class,

de.fu_berlin.inf.dpp.synchronize.TestSuite.class })
public class SarosCoreTestSuite {
    // the class remains completely empty,
    // being used only as a holder for the above annotations
}