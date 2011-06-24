package org.sonatype.nexus.plugins.p2.repository.its.p2r03;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URL;

import org.junit.Test;
import org.sonatype.nexus.plugins.p2.repository.its.AbstractNexusP2GeneratorIT;

public class P2R0302P2RepositoryDeployBundleIT
    extends AbstractNexusP2GeneratorIT
{

    public P2R0302P2RepositoryDeployBundleIT()
    {
        super( "p2r03" );
    }

    /**
     * When a bundle is deployed p2Artifacts/p2Content gets created and added to the top generated p2 repository.
     */
    @Test
    public void test()
        throws Exception
    {
        createP2MetadataGeneratorCapability();
        createP2RepositoryGeneratorCapability();

        deployArtifacts( getTestResourceAsFile( "artifacts/jars" ) );

        // ensure link created
        final File file =
            downloadFile(
                new URL( getNexusTestRepoUrl() + "/.p2/plugins/org.ops4j.base.lang_1.2.3.jar" ),
                new File( "target/downloads/" + this.getClass().getSimpleName() + "/org.ops4j.base.lang_1.2.3.jar" ).getCanonicalPath() );
        assertTrue( file.canRead() );

        // ensure repositories are valid
        final File installDir = new File( "target/eclipse/p2r0302" );

        installUsingP2( getNexusTestRepoUrl() + "/.p2", "org.ops4j.base.lang", installDir.getCanonicalPath() );

        final File bundle = new File( installDir, "plugins/org.ops4j.base.lang_1.2.3.jar" );
        assertTrue( bundle.canRead() );
    }
}
