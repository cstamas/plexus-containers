package org.codehaus.plexus.component.repository;

import junit.framework.TestCase;
import org.codehaus.plexus.PlexusTools;

import java.util.Set;
import java.util.Iterator;

/**
 *
 *
 * @author <a href="mailto:jason@maven.org">Jason van Zyl</a>
 *
 * @version $Id$
 */
public class ComponentSetTest
    extends TestCase
{
    public void testSimpleComponentResolution()
        throws Exception
    {
        String cc1 =
            "<component-set>" +
            "  <components>" +
            "    <component>" +
            "      <role>c1</role>" +
            "      <role-hint>role-hint</role-hint>" +
            "      <component-profile>component-profile</component-profile>" +
            "      <requirements>" +
            "        <requirement>" +
            "          <role>c2</role>" +
            "        </requirement>" +
            "        <requirement>" +
            "          <role>c3</role>" +
            "        </requirement>" +
            "      </requirements>" +
            "    </component>" +
            "  </components>" +
            "  <dependencies>" +
            "    <dependency>" +
            "      <group-id>plexus</group-id>" +
            "      <artifact-id>wedgy</artifact-id>" +
            "      <version>1.0</version>" +
            "    </dependency>" +
            "  </dependencies>" +
            "</component-set>";

        ComponentSet cs = PlexusTools.buildComponentSet( PlexusTools.buildConfiguration( cc1 ) );

        ComponentDescriptor c1 = (ComponentDescriptor) cs.getComponents().get( 0 );

        assertEquals( "c1", c1.getRole() );

        assertEquals( "role-hint", c1.getRoleHint() );

        assertEquals( "component-profile", c1.getComponentProfile() );

        Set requirements = c1.getRequirements();

        assertEquals( 2, requirements.size() );

        boolean containsC2 = false;

        boolean containsC3 = false;

        for ( Iterator iterator = requirements.iterator(); iterator.hasNext(); )
        {
            ComponentRequirement requirement = (ComponentRequirement) iterator.next();

            if ( requirement.getRole().equals( "c2" ) )
            {
                containsC2 = true;
            }
            else if ( requirement.getRole().equals( "c3" ) )
            {
                containsC3 = true;
            }

        }

        assertTrue( containsC2 );

        assertTrue( containsC3 );

        ComponentDependency d1 = (ComponentDependency) cs.getDependencies().get( 0 );

        assertEquals( "plexus", d1.getGroupId() );

        assertEquals( "wedgy", d1.getArtifactId() );

        assertEquals( "1.0", d1.getVersion() );
    }
}
