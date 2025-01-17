<%@ page language="java" contentType="text/html; charset=utf-8" errorPage="/error.action" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core"      prefix="c" %>
<%@ taglib uri="https://emm.agnitas.de/jsp/jsp/common"  prefix="emm"%>
<%@ taglib prefix="mvc" uri="https://emm.agnitas.de/jsp/jsp/spring" %>

<%--@elvariable id="sidemenu_active" type="java.lang.String"--%>
<%--@elvariable id="sidemenu_sub_active" type="java.lang.String"--%>

<%--@elvariable id="_navigation_switch" type="java.lang.String"--%>
<%--@elvariable id="_navigation_isHighlightKey" type="java.lang.Boolean"--%>
<%--@elvariable id="_navigation_token" type="java.lang.String"--%>
<%--@elvariable id="_navigation_href" type="java.lang.String"--%>
<%--@elvariable id="_navigation_isHideAnyCase" type="java.lang.Boolean"--%>
<%--@elvariable id="_navigation_navMsg" type="java.lang.String"--%>
<%--@elvariable id="_navigation_index" type="java.lang.Integer"--%>
<%--@elvariable id="_navigation_iconClass" type="java.lang.String"--%>
<%--@elvariable id="_navigation_submenu" type="java.lang.String"--%>
<%--@elvariable id="_navigation_hideForToken" type="java.lang.String"--%>
<%--@elvariable id="_navigation_upsellingRef" type="java.lang.String"--%>
<%--@elvariable id="_navigation_conditionSatisfied" type="java.lang.Boolean"--%>

<%--@elvariable id="_sub_navigation_switch" type="java.lang.String"--%>
<%--@elvariable id="_sub_navigation_isHighlightKey" type="java.lang.Boolean"--%>
<%--@elvariable id="_sub_navigation_token" type="java.lang.String"--%>
<%--@elvariable id="_sub_navigation_href" type="java.lang.String"--%>
<%--@elvariable id="_sub_navigation_isHideAnyCase" type="java.lang.Boolean"--%>
<%--@elvariable id="_sub_navigation_navMsg" type="java.lang.String"--%>
<%--@elvariable id="_sub_navigation_index" type="java.lang.Integer"--%>
<%--@elvariable id="_sub_navigation_iconClass" type="java.lang.String"--%>
<%--@elvariable id="_sub_navigation_submenu" type="java.lang.String"--%>
<%--@elvariable id="_sub_navigation_hideForToken" type="java.lang.String"--%>
<%--@elvariable id="_sub_navigation_upsellingRef" type="java.lang.String"--%>
<%--@elvariable id="_sub_navigation_conditionSatisfied" type="java.lang.Boolean"--%>

<%--@elvariable id="sidemenu_active_additional_params" type="java.util.LinkedHashMap"--%>
<%--@elvariable id="additional_param" type="com.agnitas.taglib.SideMenuAdditionalParamTag.MenuAdditionalParams"--%>


<emm:ShowNavigation navigation="sidemenu" highlightKey='${sidemenu_active}' redesigned="true">
    <c:set var="showMenuItem" value="false"/>
    <c:set var="showUpsellingPage" value="false"/>

    <emm:ShowByPermission token="${_navigation_token}">
        <c:set var="showMenuItem" value="true"/>
    </emm:ShowByPermission>

    <c:if test="${not _navigation_conditionSatisfied}">
        <c:set var="showMenuItem" value="false"/>
    </c:if>

    <c:set var="isHiddenLBMenuItem" value="${_navigation_submenu eq 'Grid' and _navigation_conditionSatisfied eq false}"/>

    <c:if test="${not showMenuItem and not empty _navigation_upsellingRef and not isHiddenLBMenuItem}">
        <c:set var="showUpsellingPage" value="true"/>
    </c:if>

    <c:if test="${not empty _navigation_hideForToken}">
        <emm:ShowByPermission token="${_navigation_hideForToken}">
            <c:set var="showMenuItem" value="false"/>
        </emm:ShowByPermission>
    </c:if>

    <c:if test="${showMenuItem or showUpsellingPage}">
        <c:set var="sideMenuItemStyles" value="${_navigation_isHighlightKey ? 'active open' : ''}"/>
        <li class="${sideMenuItemStyles}" data-action="activateSubmenu">
            <%-- Check whether at least one item is there in submenu dropdown --%>
            <c:set var="isSubmenuAvailable" value="false"/>
            <emm:ShowNavigation navigation='sidemenu_items.${_navigation_submenu}Sub' highlightKey="${_navigation_isHighlightKey ? sidemenu_sub_active : ''}" prefix="_sub" redesigned="true">
                <c:if test="${not isSubmenuAvailable}">
                    <emm:ShowByPermission token="${_sub_navigation_token}">
                        <c:set var="isSubmenuAvailable" value="true"/>
                    </emm:ShowByPermission>

                    <c:if test="${not _sub_navigation_conditionSatisfied}">
                        <c:set var="isSubmenuAvailable" value="false"/>
                    </c:if>

                    <c:if test="${not empty _sub_navigation_hideForToken}">
                        <emm:ShowByPermission token="${_sub_navigation_hideForToken}">
                            <c:set var="isSubmenuAvailable" value="false"/>
                        </emm:ShowByPermission>
                    </c:if>
                </c:if>
            </emm:ShowNavigation>

            <c:set var="sideMenuItem">
                <i class="menu-item-logo icon icon-${_navigation_iconClass}"></i>
            </c:set>

                <%-- Show advisory title for menu item shown without permission --%>
            <c:choose>
                <c:when test="${showUpsellingPage}">
                    <c:url var="upsellingLink"  value="/upselling.action" >
                        <c:param name="page" value="${_navigation_upsellingRef}"/>
                        <c:param name="featureNameKey" value="${_navigation_navMsg}"/>
                    </c:url>

                    <a href="${upsellingLink}" title="<mvc:message code="default.forbidden.tab.premium.feature" />" class="menu-item sidebar__item-icon ${sideMenuItemStyles}">
                        ${sideMenuItem}
                    </a>
                </c:when>
                <c:otherwise>
                    <c:url var="menuItemLink" value="${_navigation_href}">
                        <c:if test="${_navigation_isHighlightKey}">
                            <c:forEach var="additional_param" items="${sidemenu_active_additional_params}">
                                <c:if test="${not additional_param.forSubmenuOnly}">
                                    <c:param name="${additional_param.paramName}" value="${additional_param.paramValue}"/>
                                </c:if>
                            </c:forEach>
                        </c:if>
                    </c:url>
                    <a href="${menuItemLink}" class="menu-item sidebar__item-icon ${sideMenuItemStyles}">
                        ${sideMenuItem}
                    </a>
                </c:otherwise>
            </c:choose>


            <div class="submenu">
                <div class="submenu-header"><mvc:message code="${_navigation_navMsg}" /></div>
                    <%-- Hide sub-items for menu item shown without permission --%>
                <c:if test="${isSubmenuAvailable and not showUpsellingPage}">

                    <ul class="submenu-items-container">
                        <emm:ShowNavigation navigation='sidemenu_items.${_navigation_submenu}Sub' highlightKey="${_navigation_isHighlightKey ? sidemenu_sub_active : ''}" prefix="_sub" redesigned="true">
                            <c:set var="showSubMenuItem" value="false"/>
                            <c:url var="subItemPage" value="${_sub_navigation_href}">
                                <c:if test="${_navigation_isHighlightKey and _sub_navigation_isHighlightKey}">
                                    <c:forEach var="additional_param" items="${sidemenu_active_additional_params}">
                                        <c:param name="${additional_param.paramName}" value="${additional_param.paramValue}"/>
                                    </c:forEach>
                                </c:if>
                            </c:url>

                            <emm:ShowByPermission token="${_sub_navigation_token}">
                                <c:set var="showSubMenuItem" value="true"/>
                            </emm:ShowByPermission>

                            <c:if test="${not _sub_navigation_conditionSatisfied}">
                                <c:set var="showSubMenuItem" value="false"/>
                            </c:if>

                            <c:if test="${not showSubMenuItem and not empty _sub_navigation_upsellingRef}">
                                <c:url var="subItemPage" value="/upselling.action" >
                                    <c:param name="page" value="${_sub_navigation_upsellingRef}"/>
                                    <c:param name="featureNameKey" value="${_sub_navigation_navMsg}"/>
                                    <c:param name="sidemenuActive" value="${_navigation_navMsg}"/>
                                    <c:param name="sidemenuSubActive" value="${_sub_navigation_navMsg}"/>
                                </c:url>

                                <c:set var="showSubMenuItem" value="true"/>
                            </c:if>

                            <c:if test="${not empty _sub_navigation_hideForToken}">
                                <emm:ShowByPermission token="${_sub_navigation_hideForToken}">
                                    <c:set var="showSubMenuItem" value="false"/>
                                </emm:ShowByPermission>
                            </c:if>

                            <c:if test="${showSubMenuItem}">
                                <li>
                                    <a href="${subItemPage}" class="submenu-item ${_sub_navigation_isHighlightKey ? 'active' : ''}">
                                        <mvc:message code="${_sub_navigation_navMsg}" />
                                    </a>
                                </li>
                            </c:if>
                        </emm:ShowNavigation>
                    </ul>
                </c:if>
            </div>
        </li>
    </c:if>
</emm:ShowNavigation>
