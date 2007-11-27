/**
 * 
 */
package org.hivedb.util;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import net.sf.cglib.core.DefaultNamingPolicy;
import net.sf.cglib.core.NamingPolicy;
import net.sf.cglib.core.Predicate;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import org.hivedb.util.functional.Amass;
import org.hivedb.util.functional.DebugMap;

import sun.reflect.ReflectionFactory.GetReflectionFactoryAction;

public class GeneratedInstanceInterceptor implements MethodInterceptor {
	private PropertyChangeSupport propertySupport;
	   
	public void addPropertyChangeListener(PropertyChangeListener listener) {          
	    propertySupport.addPropertyChangeListener(listener);        
	}
	  
	public void removePropertyChangeListener(PropertyChangeListener listener) {
		propertySupport.removePropertyChangeListener(listener);
	}
	   
	private final static String IMPLEMENTS_INTERFACE_PROPERTY = "_implementsInterface";
	public static<T> T newInstance( Class<T> clazz ){
		try{
			GeneratedInstanceInterceptor interceptor = new GeneratedInstanceInterceptor();
			Enhancer e = new Enhancer();
//			e.setNamingPolicy(new ImplNamer(clazz.getName()));
			if (clazz.isInterface())
				e.setInterfaces(new Class[] {clazz, PropertySetter.class});
			else {
				List list = new ArrayList(Arrays.asList(clazz.getInterfaces()));
				list.add(PropertySetter.class);
				Class[] copy = new Class[list.size()];
				list.toArray(copy);
				e.setInterfaces(copy);
			}
			e.setCallback(interceptor);
			Object instance = e.create();
			interceptor.propertySupport = new PropertyChangeSupport( instance );
		
			((PropertySetter)instance).set(IMPLEMENTS_INTERFACE_PROPERTY, clazz);
			return (T) instance;
		}catch( Throwable e ){
			 e.printStackTrace();
			 throw new RuntimeException(e.getMessage(), e);
		}
	}
	public static<T> T newInstance( Class<T> clazz, Map<String, Object> prototype ){
		PropertySetter<T> instance = (PropertySetter<T>) newInstance(clazz);
		for (String propertyName : ReflectionTools.getPropertiesOfGetters((Class<?>)clazz))
			instance.set(propertyName, prototype.get(propertyName));
		return (T) instance;
	}
	
	Map<Object,Object> dictionary = new Hashtable<Object,Object>();
	public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
		Object retValFromSuper = null;
		try {
			if (!Modifier.isAbstract(method.getModifiers())) {
				retValFromSuper = proxy.invokeSuper(obj, args);
			}
		} 
		finally {}
	  
		String name = method.getName();
		if( name.equals("addPropertyChangeListener"))
			addPropertyChangeListener((PropertyChangeListener)args[0]);
		else if ( name.equals( "removePropertyChangeListener" ) )
			removePropertyChangeListener((PropertyChangeListener)args[0]);

		if( name.equals("set")) {
			String propName = (String) args[0];
			dictionary.put(new String( propName ), args[1]);
			propertySupport.firePropertyChange( new String( propName ) , null , args[0]);
		}
		else if( name.startsWith("set") && args.length == 1 && method.getReturnType() == Void.TYPE ) {
			char propName[] = name.substring("set".length()).toCharArray();
			propName[0] = Character.toLowerCase( propName[0] );
			dictionary.put(new String( propName ), args[0]);
			propertySupport.firePropertyChange( new String( propName ) , null , args[0]);
		}
		else if ( name.equals("getAsMap")) {
			return dictionary;
		}
		else if( name.equals("getClass")) {
			return dictionary.get(IMPLEMENTS_INTERFACE_PROPERTY);
		}
		else if( name.startsWith("get") && args.length == 0 ) {
			char propName[] = name.substring("get".length()).toCharArray();
			propName[0] = Character.toLowerCase( propName[0] );
			Object propertyValue = dictionary.get(new String( propName ));
			if (propertyValue != null)
				return propertyValue;
		}
		else if ( name.equals("hashCode")) {
			return hashCode(obj);
		}
		else if ( name.equals("equals")) {
			return obj.hashCode() == args[0].hashCode();
		}
		else if ( name.equals("toString")) {
			return new DebugMap<Object, Object>(dictionary).toString() + "###("+dictionary.hashCode()+")";
		}
			
		return retValFromSuper;
	}

	private Object hashCode(Object obj) {
		Class implementsInterface = (Class) dictionary.get(IMPLEMENTS_INTERFACE_PROPERTY);
		return Amass.makeHashCode(ReflectionTools.invokeGetters(obj, implementsInterface));
	}
	
	static class ImplNamer extends DefaultNamingPolicy {
		private String specialClass;
		
		public ImplNamer(String className) {
			this.specialClass = className;
		}
		
		public String getClassName(String prefix, String source, Object key, Predicate names) {
			return prefix.equals(specialClass) ? prefix + "Gen" : super.getClassName(prefix, source, key, names);
		}
	}
}