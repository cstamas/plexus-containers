package org.codehaus.plexus.component.discovery;

import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.xml.xstream.PlexusTools;

import java.util.ArrayList;
import java.util.List;
import java.io.Reader;

/**
 *
 *
 * @author <a href="mailto:jason@maven.org">Jason van Zyl</a>
 *
 * @version $Id$
 */
public class DefaultComponentDiscoverer
    extends AbstractComponentDiscoverer
{
    public String getComponentDescriptorLocation()
    {
        return "META-INF/plexus/components.xml";
    }

    public String getComponentType()
    {
        return "plexus";
    }

    public List createComponentDescriptors( Reader componentDescriptorReader )
        throws Exception
    {
        PlexusConfiguration componentDescriptorConfiguration = PlexusTools.buildConfiguration( componentDescriptorReader );

        List componentDescriptors = new ArrayList();

        PlexusConfiguration[] componentConfigurations =
            componentDescriptorConfiguration.getChild( "components" ).getChildren( "component" );

        for ( int i = 0; i < componentConfigurations.length; i++ )
        {
            PlexusConfiguration componentConfiguration = componentConfigurations[i];

            componentDescriptors.add( PlexusTools.buildComponentDescriptor( componentConfiguration ) );
        }

        return componentDescriptors;
    }
}
