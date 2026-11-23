package sh.btx.archipelago;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

// Paper's suites only select Paper's packages
@Suite
@SuiteDisplayName("Test suite for Archipelago, with registry values present")
@IncludeTags("AllFeatures")
@SelectPackages("sh.btx.archipelago")
@ConfigurationParameter(key = "TestSuite", value = "AllFeatures")
public class ArchipelagoTestSuite {
}
