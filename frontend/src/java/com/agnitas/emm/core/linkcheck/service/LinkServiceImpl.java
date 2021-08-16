/*

    Copyright (C) 2019 AGNITAS AG (https://www.agnitas.org)

    This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
    You should have received a copy of the GNU Affero General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.

*/

package com.agnitas.emm.core.linkcheck.service;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.agnitas.beans.TagDetails;
import org.agnitas.beans.impl.TagDetailsImpl;
import org.agnitas.emm.core.commons.uid.ExtensibleUIDService;
import org.agnitas.emm.core.commons.uid.builder.impl.exception.RequiredInformationMissingException;
import org.agnitas.emm.core.commons.uid.builder.impl.exception.UIDStringBuilderException;
import org.agnitas.emm.core.commons.util.ConfigService;
import org.agnitas.emm.core.commons.util.ConfigValue;
import org.agnitas.emm.core.velocity.VelocityCheck;
import org.agnitas.util.AgnUtils;
import org.agnitas.util.NetworkUtil;
import org.agnitas.util.PubID;
import org.agnitas.util.TimeoutLRUMap;
import org.agnitas.util.UnclosedTagException;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Required;

import com.agnitas.beans.ComCompany;
import com.agnitas.beans.ComTrackableLink;
import com.agnitas.beans.LinkProperty;
import com.agnitas.beans.impl.ComTrackableLinkImpl;
import com.agnitas.dao.ComCompanyDao;
import com.agnitas.dao.ComMailingDao;
import com.agnitas.emm.core.commons.uid.ComExtensibleUID;
import com.agnitas.emm.core.commons.uid.UIDFactory;
import com.agnitas.emm.core.hashtag.HashTagContext;
import com.agnitas.emm.core.hashtag.service.HashTagEvaluationService;
import com.agnitas.emm.core.linkcheck.service.LinkService.LinkWarning.WarningType;
import com.agnitas.emm.core.mailing.bean.ComMailingParameter;
import com.agnitas.emm.core.mailing.dao.ComMailingParameterDao;
import com.agnitas.emm.grid.grid.beans.GridCustomPlaceholderType;
import com.agnitas.service.AgnTagService;
import com.agnitas.util.Caret;
import com.agnitas.util.DeepTrackingToken;
import com.agnitas.util.LinkUtils;
import com.agnitas.util.StringUtil;
import com.agnitas.util.backend.Decrypt;

public class LinkServiceImpl implements LinkService {
	
	/** The logger. */
	private static final transient Logger logger = Logger.getLogger(LinkServiceImpl.class);

	private static final Pattern HASHTAG_PATTERN = Pattern.compile("##([^#]+)##");
	private static final Pattern AGNTAG_PATTERN = Pattern.compile("\\[agn[^\\]]+]");
	private static final Pattern GRIDTAG_PATTERN = Pattern.compile("\\[gridPH[^\\]]+]");
	private static final Pattern DOCTYPE_PATTERN = Pattern.compile("<!DOCTYPE [^>]*>", Pattern.CASE_INSENSITIVE);
	private static final Pattern ACTIONID_PATTERN = Pattern.compile("\\s+actionid\\s*=\\s*((\"[0-9]+\")|('[0-9]+')|([0-9]+))", Pattern.CASE_INSENSITIVE);
	private static final Pattern TITLE_PATTERN = Pattern.compile("\\s+title\\s*=\\s*((\"[ 0-9A-Z_.+-]+\")|('[ 0-9A-Z_.+-]+')|([0-9A-Z_.+-]+))", Pattern.CASE_INSENSITIVE);
	private static final String RDIRLINK_SEARCH_STRING = "r.html?uid=";
	private static final Pattern INVALID_HREF_WITH_WHITESPACE_PATTERN = Pattern.compile("\\shref\\s*=\\s*(\"|')\\s", Pattern.CASE_INSENSITIVE);
	private static final Pattern INVALID_SRC_WITH_WHITESPACE_PATTERN = Pattern.compile("\\ssrc\\s*=\\s*(\"|')\\s", Pattern.CASE_INSENSITIVE);
	
	/**
	 * Pattern that matches the subtext before an URL.
	 * Subtext must end with &quot;xmlns=&quot; or &quot;xmlns:&lt;somename&gt;=&quot; followed by an
	 * optional &quot; or &apos;. 
	 */
	private static final Pattern XMLNS_PATTERN = Pattern.compile(".*xmlns(?::\\p{Alnum}+)?\\s*=\\s*(?:\"|')?$", Pattern.MULTILINE + Pattern.DOTALL);

	/**
	 * Pattern for URL protocol schema.
	 * 
	 * <ul>
	 *   <li>\p{alpha} &#8594; The protocol including trailing colon</li>
	 * </ul>
	 */
	static final Pattern PROTOCOL_SCHEMA_PATTERN = Pattern.compile("^(\\p{Alpha}+):.*$");
	
	/**
	 * Pattern for HTTP urls embedded in a <i>href</i>, <i>src<i> or <i>background</i> attribute.
	 * URLs is either detected by the leading attribute or by leading protocol.
	 * 
	 * <ul>
	 *   <li>((?:href|src|background)=) &#8594; leading attribute name including &quot;=&quot;
	 *   <li>(?:https?://)*https?:// &#8594; The HTTP/HTTPS protocol schema followed by a colon and two slashes. This regex also detect a sequence
	 *   of more than one protocol (used for error detection)</li>
	 *   <li>[0-9A-Z_.+-]+ &#8594; host name</li>
	 *   <li>(?::[0-9]+)? optional port number including heading colon</li>
	 * </ul>
	 */
	private static final Pattern URL_DETECTION_PATTERN = Pattern.compile("((?:href|src|background)=)|((?:https?://)*https?://[0-9A-Z_.+-]+(?::[0-9]+)?)", Pattern.CASE_INSENSITIVE);

	/**
	 * Pattern to detect multiple protocol schemata. This is used to detect erroneous links.
	 * 
	 * <ul>
	 *   <li>(\\p{Alpha}+://) &#8594; The HTTP/HTTPS protocol schema followed by a colon and two slashes.
	 * </ul>
	 */
	private static final Pattern DOUBLE_PROTOCOL_SCHEMA_PATTERN = Pattern.compile("^(\\p{Alpha}+://)(\\p{Alpha}+://).*$");

	
	private ConfigService configService;
	
	private ExtensibleUIDService extensibleUIDService;

	private AgnTagService agnTagService;

	private ComCompanyDao companyDao;
	
	private ComMailingDao mailingDao;
	
	private ComMailingParameterDao mailingParameterDao;
	
	private HashTagEvaluationService hashTagEvaluationService;

	/**
	 * Map cache from mailingID to baseUrl
	 */
	private TimeoutLRUMap<Integer, String> baseUrlCache = null;

	@Required
	public void setConfigService(ConfigService configService) {
		this.configService = configService;
	}

	@Required
	public void setExtensibleUIDService(ExtensibleUIDService extensibleUIDService) {
		this.extensibleUIDService = extensibleUIDService;
	}

	@Required
	public void setAgnTagService(AgnTagService agnTagService) {
		this.agnTagService = agnTagService;
	}

	@Required
	public void setCompanyDao(ComCompanyDao companyDao) {
		this.companyDao = companyDao;
	}
	
	@Required
	public void setMailingDao(ComMailingDao mailingDao) {
		this.mailingDao = mailingDao;
	}
	
	@Required
	public void setMailingParameterDao(ComMailingParameterDao mailingParameterDao) {
		this.mailingParameterDao = mailingParameterDao;
	}
	
	@Required
	public final void setHashTagEvaluationService(final HashTagEvaluationService service) {
		this.hashTagEvaluationService = Objects.requireNonNull(service, "HashTagEvaluationSerice is null");
	}
	
	private TimeoutLRUMap<Integer, String> getBaseUrlCache() {
		if (baseUrlCache == null) {
			baseUrlCache = new TimeoutLRUMap<>(configService.getIntegerValue(ConfigValue.RedirectKeysMaxCache), configService.getIntegerValue(ConfigValue.RedirectKeysMaxCacheTimeMillis));
		}
		return baseUrlCache;
	}

	@Override
	public String personalizeLink(final ComTrackableLink link, final String agnUidString, final int customerID, final String referenceTableRecordSelector, final boolean applyLinkExtensions, final String encryptedStaticValueMap) {
		final String fullUrlWithHashTags = link.getFullUrl();
		final Map<String, Object> staticValueMap = link.isStaticValue() ? decryptStaticValueMap(link.getCompanyID(), customerID, encryptedStaticValueMap) : Collections.emptyMap();

		String fullUrl = replaceHashTags(link, agnUidString, customerID, referenceTableRecordSelector, fullUrlWithHashTags, staticValueMap);
		
		if (applyLinkExtensions) {
			if (link.getProperties() != null && link.getProperties().size() > 0) {
				// Create redirect Url with new extensions
				for (LinkProperty linkProperty : link.getProperties()) {
					if (LinkUtils.isExtension(linkProperty)) {
						String propertyName = replaceHashTags(link, agnUidString, customerID, referenceTableRecordSelector, linkProperty.getPropertyName(), staticValueMap);
						String propertyValue = replaceHashTags(link, agnUidString, customerID, referenceTableRecordSelector, linkProperty.getPropertyValue(), staticValueMap);
						
						// Extend link properly (watch out for html-anchors etc.)
						try {
							// UrlEncoding is done separately before to prevent ##-tags from becomming encoded (=> encodingCharSet = null)
							fullUrl = AgnUtils.addUrlParameter(fullUrl, propertyName, propertyValue == null ? "" : propertyValue, "UTF-8");
						} catch (UnsupportedEncodingException e) {
							throw new RuntimeException("Cannot add link extension: " + e.getMessage(), e);
						}
					}
				}
			}
		}
		
		return fullUrl;
	}
	
	private final Map<String, Object> decryptStaticValueMap(final int companyID, final int customerID, final String encryptedStaticValueMap) {
		final ComCompany company = this.companyDao.getCompany(companyID);
		
		try {
			final Decrypt decrypt = new Decrypt(company.getSecretKey());
			final Map<String, Object> staticValueMap = decrypt.decryptAndDecode(encryptedStaticValueMap, customerID);
	
			return staticValueMap;
		} catch(final Exception e) {
			logger.error("Error decrypting static value map", e);
			
			return Collections.emptyMap();
		}
	}

	private String replaceHashTags(final ComTrackableLink link, final String agnUidString, final int customerID, final String referenceTableRecordSelector, final String textWithHashTags, final Map<String, Object> staticValueMap) {
		Matcher matcher = HASHTAG_PATTERN.matcher(textWithHashTags);
		StringBuffer returnLinkString = new StringBuffer(textWithHashTags.length());
		
		PubID pubid = null;

		
		// Find all hash-tags
		int currentPosition = 0;
		while (matcher.find(currentPosition)) {
			String hashTagContent = matcher.group(1);
			int hashTagStart = matcher.start();
			int hashTagEnd = matcher.end();
			String hashTagReplacement;
			
			final HashTagContext hashTagContext = new HashTagContext(link, customerID, agnUidString, referenceTableRecordSelector, staticValueMap);

			// Prefix the prolog of the url, which does not contain other hash-tags
			if (currentPosition < hashTagStart) {
				returnLinkString.append(textWithHashTags.substring(currentPosition, hashTagStart));
			}
			currentPosition = hashTagEnd;
			
			if (hashTagContent.equals("AGNUID")) {
				// TODO Move to own hashtag class and add to hashtag registry
				hashTagReplacement = agnUidString;
			} else if (hashTagContent.equals("MAILING_ID")) {
				// TODO Move to own hashtag class and add to hashtag registry
				hashTagReplacement = Integer.toString(link.getMailingID());
			} else if (hashTagContent.equals("URL_ID")) {
				// TODO Move to own hashtag class and add to hashtag registry
				hashTagReplacement = Integer.toString(link.getId());
			} else if (hashTagContent.equals("PUBID") || hashTagContent.startsWith("PUBID:")) {
				// TODO Move to own hashtag classes and add to hashtag registry
				String[] parts = hashTagContent.split(":", 3);

				if (pubid == null) {
					pubid = new PubID();
					pubid.setMailingID(link.getMailingID());
					pubid.setCustomerID(customerID);
				}
				
				if (parts.length > 1) {
					pubid.setSource(parts[1].length() > 0 ? parts[1] : null);
					if (parts.length > 2) {
						pubid.setParm(parts[2]);
					} else {
						pubid.setParm(null);
					}
				} else {
					pubid.setSource(null);
					pubid.setParm(null);
				}
				
				hashTagReplacement = pubid.createID();
			} else if (hashTagContent.startsWith("SENDDATE-UNENCODED:")) {
				// TODO Move to own hashtag class and add to hashtag registry
				String format = hashTagContent.substring(hashTagContent.indexOf(':') + 1);
				
				try {
					Date sendDate = mailingDao.getSendDate(link.getCompanyID(), link.getMailingID());
					if (sendDate != null) {
						// Legacy: old examples in documentation said "mm" stands for month-2digits (db convention), so it must be replaced for Java format conventions into "MM"
						// Legacy: old examples in documentation said "DD" stands for day-2digits (db convention), so it must be replaced for Java format conventions into "dd"
						hashTagReplacement = new SimpleDateFormat(format.replace("mm", "MM").replace("DD", "dd")).format(sendDate);
					} else {
						hashTagReplacement = "";
					}
				} catch (Exception e) {
					hashTagReplacement = "";
				}
				
				if (hashTagReplacement == null) {
					hashTagReplacement = "";
				}
			} else if (hashTagContent.startsWith("MAILING:") || hashTagContent.startsWith("MAILING-UNENCODED:")) {
				// TODO Move to own hashtag classes and add to hashtag registry
				try {
					String paramName = hashTagContent.substring(hashTagContent.indexOf(':') + 1);
					ComMailingParameter parameter = mailingParameterDao.getParameterByName(paramName, link.getMailingID(), link.getCompanyID());
					
					if (parameter != null && parameter.getValue() != null) {
						hashTagReplacement = parameter.getValue();
						
						// If tag is "MAILING:" then URL-encode content
						if (hashTagContent.startsWith("MAILING:")) {
							hashTagReplacement = URLEncoder.encode(hashTagReplacement, "UTF8");
						}
					} else {
						hashTagReplacement = "";
					}
				} catch (Exception e) {
					logger.warn("Error processing ##MAILING:...#", e);
					hashTagReplacement = "";
				}
			} else {
				// Handle hash tag by registry and evaluation service here
				try {
					hashTagReplacement = this.hashTagEvaluationService.evaluateHashTag(hashTagContext, hashTagContent);
				} catch(final Exception e) {
					logger.error("Error processing tag string: " + hashTagContent + " (company " + hashTagContext.getCompanyID() + ", link " + hashTagContext.getCurrentTrackableLink().getId() + ")", e);

					hashTagReplacement = "";
				}
			}
			
			if (hashTagReplacement != null) {
				returnLinkString.append(hashTagReplacement);
			}
		}
		
		if (currentPosition < textWithHashTags.length()) {
			// Append the rest of the url, which does not contain other hash-tags
			returnLinkString.append(textWithHashTags.substring(currentPosition));
		}
		
		return returnLinkString.toString();
	}
	
	/**
	 * Scan a text for http and https links.
	 * First list includes simple trackable links.
	 * Second list includes image links.
	 * Third list includes simple NOT trackable links.
	 * DOCTYPE-Links will be ignored.
	 * Link strings may include Agnitas specific HashTags (##tagname: parameter='list'##).
	 * 
	 * AgnTag [agnPROFILE] will be resolved.
	 * AgnTag [agnUNSUBSCRIBE] will be resolved.
	 * AgnTag [agnFORM] will be resolved.
	 * @throws Exception
	 */
	// TODO: Check availablility and mimetype of image links
	@Override
	public LinkScanResult scanForLinks(String text, int mailingID, int mailinglistID, int companyID) throws Exception {
		if (StringUtils.isBlank(text)) {
			return new LinkScanResult();
		}
		
		return scanForLinks(resolveAgnTags(text, mailingID, mailinglistID, companyID), companyID);
	}
	
	/**
	 * Scan a text for http and https links.
	 * First list includes simple trackable links.
	 * Second list includes image links.
	 * Third list includes simple NOT trackable links.
	 * DOCTYPE-Links will be ignored.
	 * Link strings may include Agnitas specific HashTags (##tagname: parameter='list'##).
	 *
	 * AgnTag [agnPROFILE] ARE NOT resolved.
	 * AgnTag [agnUNSUBSCRIBE] ARE NOT resolved.
	 * AgnTag [agnFORM] ARE NOT resolved.
	 * @throws Exception
	 */
	// TODO: Check availablility and mimetype of image links
	@Override
	public LinkScanResult scanForLinks(String textWithDoc, int companyID) throws Exception {
		if (StringUtils.isBlank(textWithDoc)) {
			return new LinkScanResult();
		}

		// Remove doctype-tag for scan
		Matcher aMatch = DOCTYPE_PATTERN.matcher(textWithDoc);
		final String text = aMatch.find() ? textWithDoc.substring(aMatch.end()) : textWithDoc;
		final String textWithReplacedHashTags = getTextWithReplacedAgnTags(text, "x");

		final List<ComTrackableLink> foundTrackableLinks = new ArrayList<>();
		final List<String> foundImages = new ArrayList<>();
		final List<String> foundNotTrackableLinks = new ArrayList<>();
		final List<ErrorneousLink> foundErrorneousLinks = new ArrayList<>();
		final List<ErrorneousLink> localLinks = new ArrayList<>();
		final List<LinkWarning> linkWarnings = new ArrayList<>();
		
		try {
			findAllLinks(textWithReplacedHashTags, (start, end) -> {
				final LinkScanContext context = new LinkScanContext(text, start, end, foundTrackableLinks, foundImages, foundNotTrackableLinks, foundErrorneousLinks, localLinks, linkWarnings);
				doLinkChecks(context);
			});
		} catch (RuntimeException e) {
			throw new Exception(e);
		}

		return new LinkScanResult(foundTrackableLinks, foundImages, foundNotTrackableLinks, foundErrorneousLinks, localLinks, linkWarnings);
	}
	
	private final void doLinkChecks(final LinkScanContext context) {
		
		// Do not check URLs that are values of attributes "xmlns" or "xmlns:<...>"
		if(linkCheckIsNamespace(context)) {
			return;
		}
		
		// Skip links with schema no starting with "http:" or "https:"
		if(linkCheckIsProtocolSchemaPresent(context) && !linkCheckIsHttpOrHttpsSchema(context)) {
			return;
		}
		
		if(linkCheckIsLocalUrl(context)) {
			context.getLocalLinks().add(new ErrorneousLink("error.mailing.url.local", context.getStart(), context.getLinkUrl()));
			return;
		}

		if(linkCheckUrlContainsBlanks(context)) {
			context.getFoundErrorneousLinks().add(new ErrorneousLink("error.mailing.url.blank", context.getStart(), context.getLinkUrl()));
			return;
		}
		
		if(linkCheckHasMultipleProtocols(context)) {
			context.getFoundErrorneousLinks().add(new ErrorneousLink("error.mailing.url.multipleProtocols", context.getStart(), context.getLinkUrl()));
			return;
		}
		
		// Checks depending on whether link refers to an image or not
		if(linkCheckIsImageUrl(context)) {
			// URLs refers to image
			doImageLinkCheck(context);
		} else {
			// URLs refers to non-image
			doNonImageLinkCheck(context);
		}
	}
	
	private final void doNonImageLinkCheck(final LinkScanContext context) {
		if(linkCheckContainsAgnFormTag(context)) { // In some cases, the agnFORM tags cannot be resolved, so we must exclude them here
			
		} else if (linkCheckContainsAgnTagOrGridPhTag(context)) {
			context.getFoundNotTrackableLinks().add(context.getLinkUrl());
		} else if (linkCheckIsHttpOrHttpsSchema(context)) {
			final ComTrackableLink link = new ComTrackableLinkImpl();
			link.setFullUrl(context.getLinkUrl());
			link.setActionID(getActionIdForLink(context));
			link.setAltText(getTitleForLink(context));
			link.setShortname(getTitleForLink(context));
			context.getFoundTrackableLinks().add(link);
			
			if(linkCheckIsInsecureProtocol(context)) {
				context.getLinkWarnings().add(new LinkWarning(WarningType.INSECURE, context.getLinkUrl()));
			}
		} else if (!linkCheckIsAnchor(context)) {
			context.getLocalLinks().add(new ErrorneousLink("error.mailing.url.local", context.getStart(), context.getLinkUrl()));
		}		
	}
	
	private final void doImageLinkCheck(final LinkScanContext context) {
		if (linkCheckContainsHashTag(context)) {
			// No hash tags in image links allowed
			context.getFoundErrorneousLinks().add(new ErrorneousLink("error.mailing.imagelink.hash", context.getStart(), context.getLinkUrl()));
		} else {
			if(!linkCheckIsProtocolSchemaPresent(context) || linkCheckIsHttpOrHttpsSchema(context)) {
				context.getFoundImages().add(context.getLinkUrl());
				
				if(linkCheckIsInsecureProtocol(context)) {
					context.getLinkWarnings().add(new LinkWarning(WarningType.INSECURE, context.getLinkUrl()));
				}
			} else if (linkCheckContainsAgnTagOrGridPhTag(context)) {
				context.getFoundImages().add(context.getLinkUrl());

				if(linkCheckIsInsecureProtocol(context)) {
					context.getLinkWarnings().add(new LinkWarning(WarningType.INSECURE, context.getLinkUrl()));
				}
			} else {
				// No HTTP/HTTPS protocol and no AGN tag? -> Treat at local link
				context.getLocalLinks().add(new ErrorneousLink("error.mailing.url.local", context.getStart(), context.getLinkUrl()));
			}
		}
	}
	
	private final boolean linkCheckIsNamespace(final LinkScanContext context) {
		final String textBefore = context.getFullText().substring(0, context.getStart());
		final Matcher m = XMLNS_PATTERN.matcher(textBefore);
		final boolean isNamespace = m.matches();
			
		return isNamespace;
	}
	
	private final boolean linkCheckIsLocalUrl(final LinkScanContext context) {
		final String schema = context.getProtocolSchema();
		
		if(schema == null) {
			return false;		// No schema present. Treat as non-local.
		}
		
		if (schema.length() < 2) {
			return true;		// Schema present, but to short. Treat as local
		}
		
		if("file".equalsIgnoreCase(schema)) {
			return true;		// "file:" schema. Treat as local
		}

		return false;			// Treat as non-local
	}
	
	private final boolean linkCheckIsProtocolSchemaPresent(final LinkScanContext context) {
		return context.getProtocolSchema() != null;
	}
	
	private final boolean linkCheckIsHttpOrHttpsSchema(final LinkScanContext context) {
		return "http".equalsIgnoreCase(context.getProtocolSchema()) 
				|| "https".equalsIgnoreCase(context.getProtocolSchema());
	}
	
	private final boolean linkCheckIsInsecureProtocol(final LinkScanContext context) {
		return "http".equalsIgnoreCase(context.getProtocolSchema());
	}
	
	private final boolean linkCheckUrlContainsBlanks(final LinkScanContext context) {
		// Check on URLs with AGN tags replaced, because AGN tags are allowed to contain blanks.
		return context.getLinkUrlWithAgnTagsReplaced().trim().contains(" ");
	}
	
	private final boolean linkCheckHasMultipleProtocols(final LinkScanContext context) {
		final Matcher matcher = DOUBLE_PROTOCOL_SCHEMA_PATTERN.matcher(context.getLinkUrl());

		return matcher.matches();
	}
	
	private final boolean linkCheckIsImageUrl(final LinkScanContext context) {
		return AgnUtils.checkPreviousTextEquals(context.getFullText(), context.getStart(), "src=", ' ', '\'', '"', '\n', '\r', '\t')
				|| AgnUtils.checkPreviousTextEquals(context.getFullText(), context.getStart(), "background=", ' ', '\'', '"', '\n', '\r', '\t')
				|| AgnUtils.checkPreviousTextEquals(context.getFullText(), context.getStart(), "url(", ' ', '\'', '"', '\n', '\r', '\t');
	}
	
	private final boolean linkCheckContainsHashTag(final LinkScanContext context) {
		return HASHTAG_PATTERN.matcher(context.getLinkUrl()).find();
	}
	
	private final boolean linkCheckContainsAgnFormTag(final LinkScanContext context) {
		return context.getLinkUrl().contains("[agnFORM");
	}
	
	private final boolean linkCheckContainsAgnTagOrGridPhTag(final LinkScanContext context) {
		return context.getLinkUrl().contains("[agn")
				|| context.getLinkUrl().contains("[gridPH");
	}
	
	private final boolean linkCheckIsAnchor(final LinkScanContext context) {
		return context.getLinkUrl().startsWith("#");
	}

	@Override
	public final void findAllLinks(final String text, final BiConsumer<Integer, Integer> consumer) {
		int position = 0;
		final Matcher matcher = URL_DETECTION_PATTERN.matcher(text);
		while (matcher.find(position)) {
			int end; // Position of the last character of the URL (quotes not included)
			int start = matcher.start(); // Position of the first character of the URL (quotes not included)
			
			if(matcher.group(1) != null) { // Matches starting with "src=", "href=" or "background="
				final int attributeLength = matcher.group(1).length();
				start += attributeLength;	// Skip attribute

				// Skip leading blanks and tabs
				while(Character.isWhitespace(text.charAt(start))) {
					start++;
				}
							
				switch(text.charAt(start)) {
				case '\'':
					start++; // Skip quote
					end = text.indexOf('\'', start);
					break;
					
				case '"':
					start++;	// Skip quote
					end = text.indexOf('\"', start);
					break;
					
				default:
					end = AgnUtils.getNextIndexOf(text, start, ' ', '>', '\n', '\r', '\t');
					if (end == -1) {
						// Link does not end before eof
						end = text.length();
					}
					break;
				}
				
				if (end < 0) {
					throw createParseException("Missing closing apostrophe char for link url at position: ", matcher, text);
				}
			} else { // Matches starting with http:// or https://
				final char previousChar = AgnUtils.getCharacterBefore(text, matcher.start());
				if (previousChar == 0) {
					end = AgnUtils.getNextIndexOf(text, matcher.start(), ' ', '>', '\n', '\r', '\t');
					if (end == -1) {
						// Link does not end before eof
						end = text.length();
					}
				} else if (previousChar == '\'') {
					end = text.indexOf('\'', matcher.start());
					if (end < 0) {
						throw createParseException("Missing closing apostrophe char for link url at position: ", matcher, text);
					}
				} else if (previousChar == '"') {
					end = text.indexOf('"', matcher.start());
					if (end < 0) {
						throw createParseException("Missing closing quote char for link url at position: ", matcher, text);
					}
				} else if (previousChar == '(') {
					end = text.indexOf(')', matcher.start());
					if (end < 0) {
						throw createParseException("Missing closing bracket char for link url at position: ", matcher, text);
					}
				} else if (previousChar == '>') {
					end = text.indexOf('<', matcher.start());
					if (end < 0) {
						throw createParseException("Missing enclosing tag end for link url at position: ", matcher, text);
					}
				} else {
					end = AgnUtils.getNextIndexOf(text, matcher.start(), ' ', '>', '\n', '\r', '\t');
					if (end == -1) {
						// Link does not end before eof
						end = text.length();
					}
				}
			}

			consumer.accept(start, end);

			if (end >= text.length()) {
				return;
			} else {
				position = end;
			}
		}
	}

	/**
	 * Replace agnTags
	 * 
	 * AgnTag [agnPROFILE] will be resolved.
	 * AgnTag [agnUNSUBSCRIBE] will be resolved.
	 * AgnTag [agnFORM] will be resolved.
	 * 
	 * @param text
	 * @param mailingID
	 * @param mailinglistID
	 * @param companyID
	 * @return
	 */
	private final String resolveAgnTags(String text, int mailingID, int mailinglistID, int companyID) {
		if (text.contains("[agnPROFILE]")) {
			TagDetails tag = new TagDetailsImpl();
			tag.setTagName("agnPROFILE");
			text = text.replaceAll("\\[agnPROFILE\\]", agnTagService.resolve(tag, companyID, mailingID, mailinglistID, 0));
		}

		if (text.contains("[agnUNSUBSCRIBE]")) {
			TagDetails tag = new TagDetailsImpl();
			tag.setTagName("agnUNSUBSCRIBE");
			text = text.replaceAll("\\[agnUNSUBSCRIBE\\]", agnTagService.resolve(tag, companyID, mailingID, mailinglistID, 0));
		}
		
		// TODO: Resolve all agnForm-Tags with their different form names separately
		if (text.contains("[agnFORM")) {
			try {
				TagDetails tag;
				int startIndexOfAgnFormTag = text.indexOf("[agnFORM");
				int endIndexOfAgnFormTag = text.indexOf("]", startIndexOfAgnFormTag + 8);

				if (endIndexOfAgnFormTag == -1) {
					// if the Tag-Closing Bracket is missing, throw an exception
					throw new UnclosedTagException(0, "agnFORM");
				} else {
					endIndexOfAgnFormTag++;
					tag = new TagDetailsImpl();
					tag.setTagName("agnFORM");
					tag.setStartPos(startIndexOfAgnFormTag);
					tag.setEndPos(endIndexOfAgnFormTag);
					tag.setFullText(text.substring(startIndexOfAgnFormTag, endIndexOfAgnFormTag));
				}
				int startIndexOfFormName = StringUtil.firstIndexOf(tag.getFullText(), '\'', '"') + 1;
				int endIndexOfFormName = StringUtil.firstIndexOf(tag.getFullText(), startIndexOfFormName, '\'', '"');
				tag.setName(tag.getFullText().substring(startIndexOfFormName, endIndexOfFormName));
				text = text.replaceAll("\\[agnFORM\\s+name=(?:(?:\"|').*?(?:\"|')|[^\\]\\s]+)\\s*\\]", agnTagService.resolve(tag, companyID, mailingID, mailinglistID, 0));

				if (text.contains("[agnFORM")) {
					logger.error(String.format("scanForLinks: Html-text has an unresolved agnFORM-Tag [%s]", text));
				}
			} catch (Exception e) {
				logger.error("scanForLinks", e);
			}
		}
		return text;
	}

	@SuppressWarnings("unused")
	private String getMimeTypeOfLinkdata(String linkUrl) {
		GetMethod get = null;
		try {
			HttpClient httpClient = new HttpClient();
			NetworkUtil.setHttpClientProxyFromSystem(httpClient, linkUrl);
			httpClient.getParams().setParameter("http.connection.timeout", 5000);

			get = new GetMethod(linkUrl);
			get.setFollowRedirects(true);

			int returnCode = httpClient.executeMethod(get);
			if (returnCode == 200) {
				return get.getResponseHeader("Content-Type").getValue();
			} else {
				return "Unknown due to: ReturnCode " + returnCode;
			}
		} catch (Exception e) {
			logger.error("getMimeTypeOfLinkdata: " + e.getMessage(), e);
			return "Unknown MimeType due to: " + e.getMessage();
		} finally {
			if (get != null) {
				get.releaseConnection();
			}
		}
	}

	@Override
	public String encodeTagStringLinkTracking(int companyID, int mailingID, int linkID, int customerID) {
		String rdirDomain = getBaseUrlCache().get(mailingID);
		if (rdirDomain == null) {
			try {
				rdirDomain = mailingDao.getMailingRdirDomain(mailingID, companyID);
				getBaseUrlCache().put(mailingID, rdirDomain);
			} catch (Exception e) {
				logger.error("encodeTagStringLinkTracking", e);
				return "";
			}
		}

		try {
			final int licenseID = configService.getLicenseID();
		
			final ComExtensibleUID uid = UIDFactory.from(licenseID, companyID, customerID, mailingID, linkID);

			String uidString;
			try {
				uidString = extensibleUIDService.buildUIDString(uid);
			} catch (UIDStringBuilderException e) {
				logger.error("makeUIDString", e);
				uidString = "";
			} catch (RequiredInformationMissingException e) {
				logger.error("makeUIDString", e);
				uidString = "";
			}
			
			if (configService.getBooleanValue(ConfigValue.UseRdirContextLinks, companyID)) {
				return rdirDomain + "/r/" + uidString;
			} else {
				return rdirDomain + "/r.html?uid=" + uidString;
			}
		} catch (Exception e) {
			logger.error("Exception in UID", e);
			return "";
		}
	}

	/**
	 * Create DeepTrackingData-Tuple
	 * First = deepTracking Http-Url Params
	 * Second = Http-Cookie data string
	 * 
	 * @param companyID
	 * @param mailingID
	 * @param linkID
	 * @param customerID
	 * @return
	 */
	@Override
	public String createDeepTrackingUID(int companyID, int mailingID, int linkID, int customerID) {
		try {
			return new DeepTrackingToken(companyID, mailingID, customerID, linkID).createTokenString();
		} catch (Exception e) {
			logger.error("Error while URL-encoding", e);
			return null;
		}
	}

	private final int getActionIdForLink(final LinkScanContext context) {
		return getActionIdForLink(context.getFullText(), context.getStart(), context.getLinkUrl());
	}
	
	private int getActionIdForLink(String text, int linkStartIndex, String linkUrl) {
		int tagStartIndex = text.substring(0, linkStartIndex).lastIndexOf("<");
		if (tagStartIndex < 0) {
			return 0;
		}
		String prefix = text.substring(tagStartIndex, linkStartIndex);
		Matcher actionIdMatcher = ACTIONID_PATTERN.matcher(prefix);
		if (actionIdMatcher.find()) {
			String actionIdString = prefix.substring(actionIdMatcher.start(), actionIdMatcher.end());
			actionIdString = actionIdString.substring(actionIdString.indexOf("=") + 1).trim();
			if ((actionIdString.startsWith("\"") && actionIdString.endsWith("\""))
				|| (actionIdString.startsWith("'") && actionIdString.endsWith("'"))) {
				actionIdString = actionIdString.substring(1, actionIdString.length() - 1);
			} else {
				actionIdString = actionIdString.trim();
			}
			
			if (AgnUtils.isNumber(actionIdString)) {
				return Integer.parseInt(actionIdString);
			} else {
				return 0;
			}
		} else {
			int tagEndIndex = text.indexOf(">", linkStartIndex + linkUrl.length());
			if (tagEndIndex < 0) {
				return 0;
			}
			String postfix = text.substring(linkStartIndex + linkUrl.length(), tagEndIndex);
			actionIdMatcher = ACTIONID_PATTERN.matcher(postfix);
			if (actionIdMatcher.find()) {
				String actionIdString = postfix.substring(actionIdMatcher.start(), actionIdMatcher.end());
				actionIdString = actionIdString.substring(actionIdString.indexOf("=") + 1).trim();
				if ((actionIdString.startsWith("\"") && actionIdString.endsWith("\""))
					|| (actionIdString.startsWith("'") && actionIdString.endsWith("'"))) {
					actionIdString = actionIdString.substring(1, actionIdString.length() - 1);
				} else {
					actionIdString = actionIdString.trim();
				}
				
				if (AgnUtils.isNumber(actionIdString)) {
					return Integer.parseInt(actionIdString);
				} else {
					return 0;
				}
			} else {
				return 0;
			}
		}
	}

	private final String getTitleForLink(final LinkScanContext context) {
		return getTitleForLink(context.getFullText(), context.getStart(), context.getLinkUrl());
	}
	
	private String getTitleForLink(String text, int linkStartIndex, String linkUrl) {
		int tagStartIndex = text.substring(0, linkStartIndex).lastIndexOf("<");
		if (tagStartIndex < 0) {
			return "";
		}
		String prefix = text.substring(tagStartIndex, linkStartIndex);
		Matcher titleMatcher = TITLE_PATTERN.matcher(prefix);
		if (titleMatcher.find()) {
			String titleString = prefix.substring(titleMatcher.start(), titleMatcher.end());
			titleString = titleString.substring(titleString.indexOf("=") + 1).trim();
			if ((titleString.startsWith("\"") && titleString.endsWith("\""))
				|| (titleString.startsWith("'") && titleString.endsWith("'"))) {
				titleString = titleString.substring(1, titleString.length() - 1);
			} else {
				titleString = titleString.trim();
			}
			
			return titleString;
		} else {
			int tagEndIndex = text.indexOf(">", linkStartIndex + linkUrl.length());
			if (tagEndIndex < 0) {
				return "";
			}
			String postfix = text.substring(linkStartIndex + linkUrl.length(), tagEndIndex);
			titleMatcher = TITLE_PATTERN.matcher(postfix);
			if (titleMatcher.find()) {
				String titleString = postfix.substring(titleMatcher.start(), titleMatcher.end());
				titleString = titleString.substring(titleString.indexOf("=") + 1).trim();
				if ((titleString.startsWith("\"") && titleString.endsWith("\""))
					|| (titleString.startsWith("'") && titleString.endsWith("'"))) {
					titleString = titleString.substring(1, titleString.length() - 1);
				} else {
					titleString = titleString.trim();
				}
				
				return titleString;
			} else {
				return "";
			}
		}
	}
	
	@Override
	public Integer getLineNumberOfFirstRdirLink(final int companyID, String text) {
		final ComCompany company = this.companyDao.getCompany(companyID);
		
		final int indexOfRdirLink = text.indexOf(RDIRLINK_SEARCH_STRING);
		final int indexOfRdirLinkNewFormat = text.indexOf(company.getRdirDomain() + "/r/");
		
		if (indexOfRdirLink < 0) {
			if (indexOfRdirLinkNewFormat < 0) {
				return null;
			} else {
				return AgnUtils.getLineNumberOfTextposition(text, indexOfRdirLinkNewFormat);
			}
		} else {
			if (indexOfRdirLinkNewFormat < 0) {
				return AgnUtils.getLineNumberOfTextposition(text, indexOfRdirLink);
			} else {
				return AgnUtils.getLineNumberOfTextposition(text, Math.min(indexOfRdirLink, indexOfRdirLinkNewFormat));
			}
		}
	}

	@Override
	public int getLineNumberOfFirstInvalidLink(String text) {
		Matcher matcher = INVALID_HREF_WITH_WHITESPACE_PATTERN.matcher(text);
		if (matcher.find()) {
			return AgnUtils.getLineNumberOfTextposition(text, matcher.start());
		} else {
			return -1;
		}
	}

	@Override
	public Integer getLineNumberOfFirstInvalidSrcLink(String text) {
		Matcher matcher = INVALID_SRC_WITH_WHITESPACE_PATTERN.matcher(text);
		if (matcher.find()) {
			return AgnUtils.getLineNumberOfTextposition(text, matcher.start());
		} else {
			return null;
		}
	}

	static final String getTextWithReplacedAgnTags(String text, String replaceTo) {
		// todo: there is possible to use replaceAll(regexp, replaceTo)
		// Find all HashTags and replace them by x...x to allow standard html link scan but preserve any occurring errors and their real positions
		String textWithReplacedHashTags = text;
		int currentPosition = 0;
		Matcher hashTagMatcher = HASHTAG_PATTERN.matcher(textWithReplacedHashTags);

		while (hashTagMatcher.find(currentPosition)) {
			String fullHashTagString = text.substring(hashTagMatcher.start(), hashTagMatcher.end());
			textWithReplacedHashTags = textWithReplacedHashTags.replace(fullHashTagString, AgnUtils.repeatString(replaceTo, (fullHashTagString.length())));
			currentPosition = hashTagMatcher.end();
		}

		// Find all AgnTags and replace them by x...x to allow standard html link scan but preserve any occurring errors and their real positions
		currentPosition = 0;
		Matcher agnTagMatcher = AGNTAG_PATTERN.matcher(textWithReplacedHashTags);
		while (agnTagMatcher.find(currentPosition)) {
			String fullAgnTagString = textWithReplacedHashTags.substring(agnTagMatcher.start(), agnTagMatcher.end());
			textWithReplacedHashTags = textWithReplacedHashTags.replace(fullAgnTagString, AgnUtils.repeatString(replaceTo, (fullAgnTagString.length())));
			currentPosition = agnTagMatcher.end();
		}

		// Find all GridPH-Tags and replace them by x...x to allow standard html link scan but preserve any occurring errors and their real positions
		currentPosition = 0;
		Matcher gridTagMatcher = GRIDTAG_PATTERN.matcher(textWithReplacedHashTags);
		while (gridTagMatcher.find(currentPosition)) {
			String fullGridTagString = textWithReplacedHashTags.substring(gridTagMatcher.start(), gridTagMatcher.end());
			textWithReplacedHashTags = textWithReplacedHashTags.replace(fullGridTagString, AgnUtils.repeatString(replaceTo, (fullGridTagString.length())));
			currentPosition = gridTagMatcher.end();
		}

		return textWithReplacedHashTags;
	}

	@Override
	public String validateLink(@VelocityCheck int companyId, String link, GridCustomPlaceholderType type) {
		if (Objects.isNull(type)) {
			return null;
		}
		if (type == GridCustomPlaceholderType.ImageLink && HASHTAG_PATTERN.matcher(link).find()) {
			// No HASH-Tags in image links allowed
			return "error.mailing.image.link.hash";
		}
		String replacedLink = getTextWithReplacedAgnTags(link, "x");
		if (replacedLink.contains(" ")) {
			return "error.mailing.url.blank.param";
		}
		return null;
	}

    @Override
    public List<LinkProperty> getDefaultExtensions(@VelocityCheck int companyId) {
        String defaultExtension = configService.getValue(ConfigValue.DefaultLinkExtension, companyId);
        return LinkUtils.parseLinkExtension(defaultExtension);
    }

	private ParseLinkRuntimeException createParseException(String message, Matcher matcher, String text) {
		Caret caret = Caret.at(text, matcher.start());
		return new ParseLinkRuntimeException(message + caret, matcher.group(), caret);
	}
}