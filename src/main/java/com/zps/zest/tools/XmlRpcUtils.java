package com.zps.zest.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

public class XmlRpcUtils {
    public static String convertJsonToXml(JsonObject jsonObject) throws ParserConfigurationException, TransformerException {
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
        Document doc = docBuilder.newDocument();
        Element methodCallElement = doc.createElement("TOOL");
        doc.appendChild(methodCallElement);

        // Add methodName element
        String toolName = jsonObject.get("toolName").getAsString();
        Element methodNameElement = doc.createElement("methodName");
        methodNameElement.appendChild(doc.createTextNode(toolName));
        methodCallElement.appendChild(methodNameElement);

        // Add params element
        Element paramsElement = doc.createElement("params");
        methodCallElement.appendChild(paramsElement);

        // Add param elements
        for (String key : jsonObject.keySet()) {
            if (!key.equals("toolName")) {
                Element paramElement = doc.createElement("param");
                paramsElement.appendChild(paramElement);

                Element nameElement = doc.createElement("name");
                nameElement.appendChild(doc.createTextNode(key));
                paramElement.appendChild(nameElement);

                Element valueElement = doc.createElement("value");
                JsonElement valueJsonElement = jsonObject.get(key);
                if (valueJsonElement.isJsonPrimitive()) {
                    valueElement.appendChild(doc.createTextNode(valueJsonElement.getAsString()));
                }
                paramElement.appendChild(valueElement);
            }
        }

        // Convert Document to String
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes"); // Omit XML declaration

        DOMSource source = new DOMSource(doc);
        StringWriter writer = new StringWriter();
        StreamResult result = new StreamResult(writer);
        transformer.transform(source, result);

        return writer.toString();
    }
    public static JsonObject convertXmlToJson(String xmlText) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xmlText)));
        return convertXmlToJson(doc);
    }

    public static JsonObject convertXmlToJson(Document xml) {
        JsonObject json = new JsonObject();
        NodeList methodCallList = xml.getElementsByTagName("TOOL");
        if (methodCallList.getLength() > 0) {
            Element methodCallElement = (Element) methodCallList.item(0);
            NodeList methodNameList = methodCallElement.getElementsByTagName("methodName");
            if (methodNameList.getLength() > 0) {
                String methodName = methodNameList.item(0).getTextContent();
                json.addProperty("toolName", methodName);
            }

            NodeList paramsList = methodCallElement.getElementsByTagName("params");
            if (paramsList.getLength() > 0) {
                NodeList paramList = ((Element) paramsList.item(0)).getElementsByTagName("param");
                for (int i = 0; i < paramList.getLength(); i++) {
                    Node paramNode = paramList.item(i);
                    if (paramNode.getNodeType() == Node.ELEMENT_NODE) {
                        Element paramElement = (Element) paramNode;
                        NodeList nameList = paramElement.getElementsByTagName("name");
                        NodeList valueList = paramElement.getElementsByTagName("value");
                        if (nameList.getLength() > 0 && valueList.getLength() > 0) {
                            String paramName = nameList.item(0).getTextContent();
                            String paramValue = valueList.item(0).getTextContent();
                            json.addProperty(paramName, paramValue);
                        }
                    }
                }
            }
        }
        return json;
    }
}