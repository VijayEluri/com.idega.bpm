package com.idega.bpm.xformsview.converters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.chiba.xml.dom.DOMUtil;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.idega.block.process.variables.VariableDataType;
import com.idega.core.business.DefaultSpringBean;
import com.idega.util.CoreConstants;
import com.idega.util.StringHandler;
import com.idega.util.StringUtil;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.json.JettisonMappedXmlDriver;

/**
 * @author <a href="mailto:anton@idega.com">Anton Makarov</a>
 * @version $Revision: 1.2 $
 * 
 * Last modified: $Date: 2008/09/17 13:09:39 $ by $Author: civilis $
 */

@Service
@Scope(BeanDefinition.SCOPE_SINGLETON)
public class ObjectCollectionConverter extends DefaultSpringBean implements DataConverter {

	private static final String listElName = "list";
	private static final String rowElName = "row";

	public Object convert(Element o) {

		Element listEl = DOMUtil.getChildElement(o, listElName);
		@SuppressWarnings("unchecked")
		List<Element> rowNodes = DOMUtil.getChildElements(listEl);
		List<String> rowList = new ArrayList<String>();

		for (Element rowElem : rowNodes) {
			@SuppressWarnings("unchecked")
			List<Element> columns = DOMUtil.getChildElements(rowElem);
			Map<String, String> columnMap = new LinkedHashMap<String, String>();

			for (Element columnElem : columns) {
				columnMap.put(columnElem.getNodeName(), columnElem
						.getTextContent());
			}
			rowList.add(ObjToJSON(columnMap));
		}

		return rowList;
	}

	public Element revert(Object o, Element e) {

		if (!(o instanceof List))
			throw new IllegalArgumentException(
					"Wrong class object provided for ObjCollectionConverter: "
							+ o.getClass().getName()
							+ ". Should be java.util.List");

		e = DOMUtil.getChildElement(e, listElName);
		NodeList childNodes = e.getChildNodes();

		List<Node> childs2Remove = new ArrayList<Node>();

		for (int i = 0; i < childNodes.getLength(); i++) {

			Node child = childNodes.item(i);

			if (child != null
					&& (child.getNodeType() == Node.TEXT_NODE || child
							.getNodeType() == Node.ELEMENT_NODE))
				childs2Remove.add(child);
		}

		for (Node node : childs2Remove)
			e.removeChild(node);

		@SuppressWarnings("unchecked")
		Collection<String> rowList = (Collection<String>) o;

		for (String rowStr : rowList) {
			Map<String, String> columnMap = JSONToObj(rowStr);

			Element rowElem = e.getOwnerDocument().createElement(rowElName);

			for (String column : columnMap.keySet()) {
				Element columnEl = rowElem.getOwnerDocument().createElement(
						column);
				columnEl.setTextContent(columnMap.get(column));
				rowElem.appendChild(columnEl);
			}

			e.appendChild(rowElem);
		}
		return e;
	}

	private String ObjToJSON(Map<String, String> obj) {
		XStream xstream = new XStream(new JettisonMappedXmlDriver());
		String jsonOut = xstream.toXML(obj);
		
		try {
			Map<String, String> object = JSONToObj(jsonOut);
			if (object != null) {
				for (String key: object.keySet()) {
					String parsedValue = object.get(key);
					String originalValue = obj.get(key);
					if (!StringUtil.isEmpty(parsedValue) && !StringUtil.isEmpty(originalValue) && !originalValue.equals(parsedValue)) {
						getLogger().info("Replacing incorrectly parsed value '" + parsedValue + "' with original one '" + originalValue + "'");
						if (StringHandler.isNaturalNumber(originalValue.substring(0, 1)))
							originalValue = CoreConstants.QOUTE_MARK.concat(originalValue).concat(CoreConstants.QOUTE_MARK);
						jsonOut = StringHandler.replace(jsonOut, parsedValue, originalValue);
					}
				}
			}
		} catch (Exception e) {
			getLogger().log(Level.WARNING, "Error while ensuring that values (" + obj + ") were correctly parsed into the JSON format:\n" + jsonOut, e); 
		}
		
		return jsonOut;
	}

	private Map<String, String> JSONToObj(String jsonIn) {
		XStream xstream = new XStream(new JettisonMappedXmlDriver());
		@SuppressWarnings("unchecked")
		Map<String, String> obj = (Map<String, String>) xstream.fromXML(jsonIn);
		
		try {
			if (obj != null) {
				for (String key: obj.keySet()) {
					String parsedValue = obj.get(key);
					if (!StringUtil.isEmpty(parsedValue)) {
						int index = jsonIn.indexOf(parsedValue);
						if (index <= 0)
							continue;
						
						index--;
						int length = parsedValue.length() + 1;
						String tmp = jsonIn.substring(index, index + length);
						while (index > 0 && !tmp.startsWith(CoreConstants.COMMA) && !tmp.startsWith(CoreConstants.QOUTE_MARK)) {
							index--;
							length++;
							tmp = jsonIn.substring(index, index + length);
						}
						
						tmp = tmp.substring(1);
						if (!tmp.equals(parsedValue)) {
							getLogger().info("Value was parsed from JSON string ('" + jsonIn + "') incorrectly! Using value '" + tmp + "' instead of '" + parsedValue + "'");
							obj.put(key, tmp);
						}
					}
				}
			}
		} catch (Exception e) {
			getLogger().log(Level.WARNING, "Error while ensuring that object (" + obj + ") was re-constructed correctly from the JSON string:\n" + jsonIn, e);
		}
		
		return obj;
	}

	public VariableDataType getDataType() {
		return VariableDataType.OBJLIST;
	}
}