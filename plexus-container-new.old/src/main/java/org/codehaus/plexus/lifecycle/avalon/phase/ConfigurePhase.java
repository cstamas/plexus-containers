package org.codehaus.plexus.lifecycle.avalon.phase;

import org.apache.avalon.framework.configuration.Configurable;
import org.apache.avalon.framework.configuration.Configuration;
import org.codehaus.plexus.component.manager.ComponentManager;
import org.codehaus.plexus.lifecycle.phase.AbstractPhase;

public class ConfigurePhase
    extends AbstractPhase
{
    public void execute( Object object, ComponentManager manager )
        throws Exception
    {
        Configuration configuration = manager.getComponentDescriptor().getConfiguration();

        if ( object instanceof Configurable )
        {
            if ( null == configuration )
            {
                final String message = "configuration is null";
                throw new IllegalArgumentException( message );
            }
            ( (Configurable) object ).configure( configuration );
        }
    }
}