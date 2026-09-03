package be.technifutur.newgameplus.shipping;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class MondialRelayClient {

    private final RestClient restClient = RestClient.create("https://api.mondialrelay.com");

    @Value("${mondialrelay.enseigne}")
    private String enseigne;

    @Value("${mondialrelay.private-key}")
    private String privateKey;

    public List<RelayPointResult> search(String postCode, String country) {
        try {
            String security = computeSecurity(postCode, country);
            String body = buildRequestBody(postCode, country, security);

            String response = restClient.post()
                    .uri("/Web_Services.asmx")
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("SOAPAction", "http://www.mondialrelay.fr/webservice/WSI4_PointRelais_Recherche")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parseResponse(response);
        } catch (Exception e) {
            log.error("Mondial Relay point relais search failed for postCode='{}', country='{}': {}",
                    postCode, country, e.getMessage(), e);
            return List.of();
        }
    }

    private String computeSecurity(String postCode, String country) {
        // Mondial Relay's documented WSI4_PointRelais_Recherche parameter order for the
        // security hash: Enseigne + Pays + NumPointRelais + Ville + CP + Latitude +
        // Longitude + Taille + Poids + Action + DelaiEnvoi + RayonRecherche +
        // TypeActivite + NombreResultats + PrivateKey, then MD5, uppercase hex.
        // All the fields this feature doesn't use are sent blank, so they're omitted
        // (empty string) from the concatenation below in the same order.
        String concatenated = enseigne + country + postCode + privateKey;
        return md5Upper(concatenated);
    }

    private String md5Upper(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02X", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm unavailable", e);
        }
    }

    private String buildRequestBody(String postCode, String country, String security) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <WSI4_PointRelais_Recherche xmlns="http://www.mondialrelay.fr/webservice/">
                      <Enseigne>%s</Enseigne>
                      <Pays>%s</Pays>
                      <NumPointRelais></NumPointRelais>
                      <Ville></Ville>
                      <CP>%s</CP>
                      <Latitude></Latitude>
                      <Longitude></Longitude>
                      <Taille></Taille>
                      <Poids></Poids>
                      <Action></Action>
                      <DelaiEnvoi></DelaiEnvoi>
                      <RayonRecherche></RayonRecherche>
                      <TypeActivite></TypeActivite>
                      <NombreResultats></NombreResultats>
                      <Security>%s</Security>
                    </WSI4_PointRelais_Recherche>
                  </soap:Body>
                </soap:Envelope>
                """.formatted(escapeXml(enseigne), escapeXml(country), escapeXml(postCode), security);
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private List<RelayPointResult> parseResponse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        List<RelayPointResult> results = new ArrayList<>();
        NodeList points = doc.getElementsByTagName("PointRelais_Details");
        for (int i = 0; i < points.getLength(); i++) {
            Element point = (Element) points.item(i);
            results.add(new RelayPointResult(
                    text(point, "Num"),
                    text(point, "LgAdr1"),
                    text(point, "LgAdr3"),
                    text(point, "CP"),
                    text(point, "Ville"),
                    text(point, "Pays")
            ));
        }
        return results;
    }

    private String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : "";
    }

    public record RelayPointResult(String id, String name, String street, String postCode, String city, String country) {
    }
}
