package org.codehaus.plexus.component.configurator.converters;

import org.codehaus.plexus.component.configurator.ComponentConfigurationException;
import org.codehaus.plexus.component.repository.ComponentDescriptor;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfigurationException;
import org.codehaus.plexus.util.StringUtils;

/**
 * @author <a href="mailto:michal@codehaus.org">Michal Maczka</a>
 * @version $Id$
 */
public abstract class AbstractConfigurationConverter implements ConfigurationConverter
{
    private static final String IMPLEMENTATION = "implementation";

    /**
     * We will check if user has provided a hint which class should be used for given field.
     * So we will check if something like <foo implementation="com.MyFoo"> is present in configuraion.
     * If 'implementation' hint was provided we will try to load correspoding class
     * If we are unable to do so error will be reported
     */
    protected Class getClassForImplementationHint( Class type,
                                                   PlexusConfiguration configuration,
                                                   ClassLoader classLoader,
                                                   ComponentDescriptor componentDescriptor )
            throws ComponentConfigurationException
    {        
        Class retValue = type;

        String implementation = null;

        try
        {
            implementation = configuration.getAttribute( IMPLEMENTATION );
        }
        catch ( Exception e )
        {
            //@todo I am not sure about semantics of getAttribute  and why it throws
            // any exception. IMO it should return null values.
        }

        if ( implementation != null )
        {
            try
            {
                retValue = classLoader.loadClass( implementation );

            }
            catch ( ClassNotFoundException e )
            {
                String msg = "Error configuring component: "
                        + componentDescriptor.getHumanReadableKey()
                        + ". Class name which was explicitly "
                        + " given in configuration using 'implementation' attribute: '"
                        + implementation + "' cannot be loaded: " + e.getMessage();

                throw new ComponentConfigurationException( msg );
            }
        }
        else
        {
            System.out.println( "no implementation hint: " + configuration.getName() );
        }
        System.out.println( "Class for hint " + type + " is:" + retValue );

        return retValue;
    }


    protected Class loadClass( String classname, ClassLoader classLoader, ComponentDescriptor componentDescriptor ) throws ComponentConfigurationException
    {
        Class retValue = null;

        try
        {
            retValue = classLoader.loadClass( classname );
        }
        catch ( Exception e )
        {
            String msg = "Error configuring component: "
                    + componentDescriptor.getHumanReadableKey()
                    + ". Class '"
                    + classname
                    + "' cannot be loaded";

            throw new ComponentConfigurationException( msg );
        }

        return retValue;

    }

    protected Object instantiateObject( String classname, ClassLoader classLoader, ComponentDescriptor componentDescriptor ) throws ComponentConfigurationException
    {
        Class clazz = loadClass( classname, classLoader, componentDescriptor );

        Object retValue = instantiateObject( clazz, componentDescriptor );

        return retValue;
    }

    protected Object instantiateObject( Class clazz, ComponentDescriptor componentDescriptor ) throws ComponentConfigurationException
    {
        Object retValue = null;

        try
        {
            retValue = clazz.newInstance();

            return retValue;
        }
        catch ( Exception e )
        {
            String msg = "Error configuring component: "
                    + componentDescriptor.getHumanReadableKey()
                    + ". Class '"
                    + clazz.getName()
                    + "' cannot be instantiated";


            throw new ComponentConfigurationException( msg );
        }
    }


    // first-name --> firstName
    protected String fromXML( String elementName )
    {
        return StringUtils.lowercaseFirstLetter( StringUtils.removeAndHump( elementName, "-" ) );
    }

    // firstName --> first-name
    protected  String toXML( String fieldName )
    {
        return StringUtils.addAndDeHump( fieldName );
    }

}
