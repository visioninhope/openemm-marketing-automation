/*

    Copyright (C) 2022 AGNITAS AG (https://www.agnitas.org)

    This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
    You should have received a copy of the GNU Affero General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.

*/

package com.agnitas.emm.core.admin.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;

import org.agnitas.beans.AdminEntry;
import org.agnitas.beans.impl.PaginatedListImpl;
import org.agnitas.emm.company.service.CompanyService;
import org.agnitas.emm.core.commons.password.PasswordCheck;
import org.agnitas.emm.core.commons.password.PasswordCheckHandler;
import org.agnitas.emm.core.commons.password.SpringPasswordCheckHandler;
import org.agnitas.emm.core.commons.password.util.PasswordPolicyUtil;
import org.agnitas.emm.core.commons.util.ConfigService;
import org.agnitas.emm.core.commons.util.ConfigValue;
import org.agnitas.service.UserActivityLogService;
import com.agnitas.service.WebStorage;
import org.agnitas.util.HttpUtils;
import org.agnitas.util.Tuple;
import org.agnitas.web.forms.FormSearchParams;
import org.agnitas.web.forms.FormUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.agnitas.beans.AdminPreferences;
import com.agnitas.beans.Admin;
import com.agnitas.beans.PollingUid;
import com.agnitas.emm.core.admin.form.AdminForm;
import com.agnitas.emm.core.admin.form.AdminListForm;
import com.agnitas.emm.core.admin.form.AdminListFormSearchParams;
import com.agnitas.emm.core.admin.form.AdminRightsForm;
import com.agnitas.emm.core.admin.form.validation.AdminFormValidator;
import com.agnitas.emm.core.admin.service.AdminChangesLogService;
import com.agnitas.emm.core.admin.service.AdminGroupService;
import com.agnitas.emm.core.admin.service.AdminSavingResult;
import com.agnitas.emm.core.admin.service.AdminService;
import com.agnitas.emm.core.logon.service.ComLogonService;
import com.agnitas.emm.core.logon.web.LogonController;
import com.agnitas.emm.core.target.service.ComTargetService;
import com.agnitas.service.ComCSVService;
import com.agnitas.service.PdfService;
import com.agnitas.service.ServiceResult;
import com.agnitas.web.mvc.Pollable;
import com.agnitas.web.mvc.Popups;
import com.agnitas.web.mvc.XssCheckAware;
import com.lowagie.text.DocumentException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import static org.agnitas.util.Const.Mvc.CHANGES_SAVED_MSG;
import static org.agnitas.util.Const.Mvc.MESSAGES_VIEW;
import static org.agnitas.util.Const.Mvc.SELECTION_DELETED_MSG;

public class AdminController implements XssCheckAware {

    private static final Logger LOGGER = LogManager.getLogger(AdminController.class);
    
    private static final String ADMIN_ENTRIES_KEY = "adminEntries";
    private static final String USERNAME_DUPLICATE_MSG = "error.username.duplicate";
    private static final String REDIRECT_TO_LIST = "redirect:/admin/list.action";
    private static final String SAVE_ERROR_MSG = "error.admin.save";

    protected final ConfigService configService;
    protected final AdminService adminService;
    protected final UserActivityLogService userActivityLogService;
    private final ComLogonService logonService;
    private final CompanyService companyService;
    private final AdminGroupService adminGroupService;
    private final WebStorage webStorage;
    private final AdminChangesLogService adminChangesLogService;
    private final PasswordCheck passwordCheck;
    private final ComCSVService csvService;
    private final PdfService pdfService;
    private final ConversionService conversionService;
    protected final ComTargetService targetService;

    protected static final String FUTURE_TASK = "GET_ADMIN_LIST";

    public AdminController(ConfigService configService,
                           AdminService adminService,
                           CompanyService companyService,
                           AdminGroupService adminGroupService, WebStorage webStorage,
                           UserActivityLogService userActivityLogService,
                           AdminChangesLogService adminChangesLogService,
                           PasswordCheck passwordCheck, ComCSVService csvService, PdfService pdfService,
                           ConversionService conversionService,
                           ComTargetService targetService,
                           ComLogonService logonService) {
        this.configService = configService;
        this.adminService = adminService;
        this.companyService = companyService;
        this.adminGroupService = adminGroupService;
        this.webStorage = webStorage;
        this.userActivityLogService = userActivityLogService;
        this.adminChangesLogService = adminChangesLogService;
        this.passwordCheck = passwordCheck;
        this.csvService = csvService;
        this.pdfService = pdfService;
        this.conversionService = conversionService;
        this.targetService = targetService;
        this.logonService = logonService;
    }

    @RequestMapping("/list.action")
    public Pollable<ModelAndView> list(final Admin admin, final AdminListForm form,
                                       final Model model, final HttpSession session,
                                       @RequestParam(value = FormSearchParams.RESET_PARAM_NAME, required = false) boolean resetSearchParams,
                                       @RequestParam(value = FormSearchParams.RESTORE_PARAM_NAME, required = false) boolean restoreSearchParams,
                                       @ModelAttribute AdminListFormSearchParams adminListSearchParams) {
        FormUtils.syncNumberOfRows(webStorage, WebStorage.ADMIN_OVERVIEW, form);
        if (resetSearchParams) {
            FormUtils.resetSearchParams(adminListSearchParams, form);
        } else {
            FormUtils.syncSearchParams(adminListSearchParams, form, restoreSearchParams);
        }
        model.addAttribute(ADMIN_ENTRIES_KEY, new PaginatedListImpl<>());
        int companyID = admin.getCompanyID();

        PollingUid pollingUid = PollingUid.builder(session.getId(), ADMIN_ENTRIES_KEY)
                .arguments(form.toArray())
                .build();

        Callable<ModelAndView> worker = () -> {
            model.addAttribute(ADMIN_ENTRIES_KEY, getAdminListFromAdminListForm(admin, form));
            form.setCompanies(companyService.getCreatedCompanies(companyID));
            form.setAdminGroups(adminGroupService.getAdminGroupsByCompanyIdAndDefault(companyID, admin, null));

            return new ModelAndView("settings_admin_list", model.asMap());
        };

        ModelAndView modelAndView = new ModelAndView(REDIRECT_TO_LIST, form.toMap());

        return new Pollable<>(pollingUid, Pollable.DEFAULT_TIMEOUT, modelAndView, worker);
    }

    @RequestMapping("/{adminID}/view.action")
    public String view(final Admin admin, final AdminForm form, final Popups popups, final Model model) {
        final int adminIdToEdit = form.getAdminID();
        final int companyID = admin.getCompanyID();
        final Admin adminToEdit = adminService.getAdmin(adminIdToEdit, companyID);
        if (adminToEdit == null) {
            return prepareErrorPageForNotLoadedAdmin(adminIdToEdit, companyID, popups, REDIRECT_TO_LIST);
        }
        if (adminToEdit.getGroups() == null || adminToEdit.getGroups().isEmpty()) {
            popups.alert("error.admin.invalidGroup");
        }

        initializeForm(form, adminToEdit, admin);

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("loadAdmin: admin {} loaded", form.getAdminID());
        }

        userActivityLogService.writeUserActivityLog(admin, "view user", adminToEdit.getUsername());

        loadDataForViewPage(admin, adminToEdit, model);
        model.addAttribute("PASSWORD_POLICY", PasswordPolicyUtil.loadCompanyPasswordPolicy(admin.getCompanyID(), configService).getPolicyName());
        model.addAttribute("EDIT_ALTG_ENABLED", canEditAltg(admin, form));

        return "settings_admin_view";
    }
    
    private static final boolean canEditAltg(final Admin admin, final AdminForm adminForm) {
    	return admin.getCompanyID() == adminForm.getCompanyID();
    }
    
    @RequestMapping("/{adminID}/welcome.action")
    public String sendWelcome(final Admin admin, final AdminRightsForm form, final Popups popups, HttpServletRequest request) {
    	String clientIp = request.getRemoteAddr();
    	final int adminIdToEdit = form.getAdminID();
    	final int companyID = admin.getCompanyID();
    	final Admin adminToEdit = adminService.getAdmin(adminIdToEdit, companyID);
    	logonService.sendWelcomeMail(adminToEdit, clientIp, LogonController.PASSWORD_RESET_LINK_PATTERN);
    	popups.success("admin.password.sent");
    	return MESSAGES_VIEW;
    }

    @RequestMapping("/{adminID}/rights/view.action")
    public String viewRights(final Admin admin, final AdminRightsForm form, final Popups popups, final Model model) {
        final int adminIdToEdit = form.getAdminID();
        final int companyID = admin.getCompanyID();
        final Admin adminToEdit = adminService.getAdmin(adminIdToEdit, companyID);
        if (adminToEdit == null) {
            return prepareErrorPageForNotLoadedAdmin(adminIdToEdit, companyID, popups, REDIRECT_TO_LIST);
        }

        prepareRightsViewPageData(admin, form, model, adminToEdit);

        return "settings_admin_rights";
    }

    @PostMapping(value = "/{adminID}/save.action")
    public ModelAndView save(final Admin admin, final AdminForm form, final Popups popups) {
        if (!AdminFormValidator.validate(form, popups)){
        	popups.alert(SAVE_ERROR_MSG);
        	return new ModelAndView(MESSAGES_VIEW, HttpStatus.BAD_REQUEST);
        }

        if (adminUsernameChangedToExisting(form)) {
            popups.alert(USERNAME_DUPLICATE_MSG);
            return new ModelAndView(MESSAGES_VIEW, HttpStatus.BAD_REQUEST);
        }

        if (StringUtils.isEmpty(form.getPassword()) || checkPassword(form, popups)) {
            admin.setRestful(false);
            saveAdminAndGetView(form, admin, popups);
            logonService.updateSessionsLanguagesAttributes(admin);
            popups.success(CHANGES_SAVED_MSG);
        }

        return new ModelAndView(redirectToView(form.getAdminID()));
    }

    private String redirectToView(int adminId) {
        return "redirect:/admin/" + adminId + "/view.action";
    }

    @PostMapping(value = "/{adminID}/rights/save.action")
    public String saveRights(final Admin admin, final AdminRightsForm form, final Popups popups, final Model model) {
        try {
            final boolean isSuccess = saveAdminRightsAndWriteToActivityLog(admin, form, popups);
            final int adminIdToEdit = form.getAdminID();
            final int companyID = admin.getCompanyID();
            final Admin adminToEdit = adminService.getAdmin(adminIdToEdit, companyID);
            prepareRightsViewPageData(admin, form, model, adminToEdit);

            if (isSuccess) {
                popups.success(CHANGES_SAVED_MSG);
            }
        } catch (Exception e) {
            LOGGER.error("Exception saving rights", e);
            popups.alert(SAVE_ERROR_MSG, e);
            return MESSAGES_VIEW;
        }
        return "settings_admin_rights";
    }

   @RequestMapping("/create.action")
    public String create(final Admin admin, @ModelAttribute("adminForm") AdminForm form, final Model model) {
       	model.addAttribute("PASSWORD_POLICY", PasswordPolicyUtil.loadCompanyPasswordPolicy(admin.getCompanyID(), configService).getPolicyName());

        loadDataForViewPage(admin, null, model);
        adminService.setDefaultPreferencesSettings(form.getAdminPreferences());

        return "settings_admin_view";
    }

    @PostMapping(value = "/saveNew.action")
    public String saveNew(final Admin admin, final AdminForm form, final Popups popups) {
        final int maximumNumberOfAdmins = configService.getIntegerValue(ConfigValue.System_License_MaximumNumberOfAdmins, admin.getCompanyID());
        if (maximumNumberOfAdmins >= 0 && maximumNumberOfAdmins <= adminService.getNumberOfGuiAdmins(admin.getCompanyID())) {
            popups.alert("error.numberOfAdminsExceeded", maximumNumberOfAdmins);
            return MESSAGES_VIEW;
        }
        form.setAdminID(0);
        if (!AdminFormValidator.validate(form, popups)) {
        	popups.alert(SAVE_ERROR_MSG);
        	return MESSAGES_VIEW;
        }
        if (StringUtils.isBlank(form.getPassword())) {
            popups.alert("error.password.missing");
            return MESSAGES_VIEW;
        }

        if (form.getGroupIDs() == null || form.getGroupIDs().isEmpty()) {
            popups.alert("error.user.group");
            return MESSAGES_VIEW;
        }

        if (adminService.adminExists(form.getUsername())) {
            popups.alert(USERNAME_DUPLICATE_MSG);
            return MESSAGES_VIEW;
        }

        if (!checkPassword(form, popups)) {
            return MESSAGES_VIEW;
        }
        
        admin.setRestful(false);

        return saveAdminAndGetView(form, admin, popups);
    }

    @RequestMapping("/list/export/csv.action")
    public Object exportCsv(final Admin admin, final AdminListForm form, final Popups popups) throws Exception {
        byte[] csvData = csvService.getUserCSV(getAdminListFromAdminListForm(admin, form, Integer.MAX_VALUE).getList());

        if (csvData == null) {
            popups.alert("error.export.file_not_ready");
            return MESSAGES_VIEW;
        }

        String description = "Page: " + form.getPage()
                + ", sort: " + form.getSort()
                + ", direction: " + form.getDir();
        userActivityLogService.writeUserActivityLog(admin, "export admins csv", description, LOGGER);
        return ResponseEntity.ok()
                .contentLength(csvData.length)
                .contentType(MediaType.parseMediaType("application/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, HttpUtils.getContentDispositionAttachment("users.csv"))
                .body(new InputStreamResource(new ByteArrayInputStream(csvData)));
    }

    @RequestMapping("/list/export/pdf.action")
    public ResponseEntity<InputStreamResource> exportPdf(final Admin admin, final AdminListForm form)
            throws IOException, DocumentException {

        byte[] pdfFileBytes = pdfService
                .writeUsersToPdfAndGetByteArray(getAdminListFromAdminListForm(admin, form, Integer.MAX_VALUE).getList());

        String description = "Page: " + form.getPage()
                + ", sort: " + form.getSort()
                + ", direction: " + form.getDir();
        userActivityLogService.writeUserActivityLog(admin, "export admins pdf", description, LOGGER);

        return ResponseEntity.ok()
                .contentLength(pdfFileBytes.length)
                .contentType(MediaType.parseMediaType("application/pdf"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        HttpUtils.getContentDispositionAttachment("users.pdf"))
                .body(new InputStreamResource(new ByteArrayInputStream(pdfFileBytes)));
    }

    @RequestMapping("/{adminID}/confirmDelete.action")
    public String confirmDelete(final Admin admin, final AdminForm form, final Popups popups) {
        final int adminIdToDelete = form.getAdminID();
        final int companyID = admin.getCompanyID();
        final ServiceResult<Admin> deleteConfirmSR = adminService.isPossibleToDeleteAdmin(adminIdToDelete, companyID);
        if (deleteConfirmSR.isSuccess()) {
            form.setUsername(deleteConfirmSR.getResult().getUsername());
            return "settings_admin_delete_ajax";
        }

        if (deleteConfirmSR.getResult() == null) {
            return prepareErrorPageForNotLoadedAdmin(adminIdToDelete, companyID, popups, REDIRECT_TO_LIST);
        }

        popups.addPopups(deleteConfirmSR);
        return MESSAGES_VIEW;
    }

    @RequestMapping("/{adminID}/delete.action")
    public String delete(final Admin admin, final AdminForm form, final Popups popups,
                         final HttpServletResponse response, final HttpSession session,
                         RedirectAttributes redirectAttributes) {
        final ServiceResult<Admin> result = adminService.delete(admin, form.getAdminID());

        if (result.isSuccess()) {
            final Admin deletedAdmin = result.getResult();

            userActivityLogService.writeUserActivityLog(admin, "delete user", deletedAdmin.getUsername() + " (" + deletedAdmin.getAdminID() + ")", LOGGER);

            if (admin.getAdminID() == deletedAdmin.getAdminID()) {
                session.invalidate();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return "redirect:/logon.action";
            }

            popups.success(SELECTION_DELETED_MSG);
        } else {
            popups.alert("Error");
        }

       redirectAttributes.addAttribute(FormSearchParams.RESTORE_PARAM_NAME, true);
        return REDIRECT_TO_LIST;
    }

    @ModelAttribute
    public AdminListFormSearchParams getAdminFormSearchParams(){
        return new AdminListFormSearchParams();
    }

    private boolean adminUsernameChangedToExisting(final AdminForm aForm) {
        final Admin currentAdmin = adminService.getAdmin(aForm.getAdminID(), aForm.getCompanyID());
        return !StringUtils.equals(currentAdmin.getUsername(), aForm.getUsername()) && adminService.adminExists(aForm.getUsername());
    }

    private boolean checkPassword(final AdminForm form, final Popups popups) {
        Admin admin = adminService.getAdmin(form.getAdminID(), form.getCompanyID());
        PasswordCheckHandler handler = new SpringPasswordCheckHandler(popups, "password");
        if (admin != null && admin.getAdminID() != 0) {
            // Existing user changes his password
            return passwordCheck.checkAdminPassword(form.getPassword(), admin, handler);
        } else {
            // New user changes wants to set his initial password
            return passwordCheck.checkNewAdminPassword(form.getPassword(), form.getCompanyID(), handler);
        }
    }

    private PaginatedListImpl<AdminEntry> getAdminListFromAdminListForm(Admin admin, AdminListForm form) {
        return getAdminListFromAdminListForm(admin, form, form.getNumberOfRows());
    }

    private PaginatedListImpl<AdminEntry> getAdminListFromAdminListForm(Admin admin, AdminListForm form, int numberOfRows) {
        return adminService.getAdminList(admin.getCompanyID(),
                form.getSearchFirstName(),
                form.getSearchLastName(),
                form.getSearchEmail(),
                form.getSearchCompany(),
                form.getFilterCompanyId(),
                form.getFilterAdminGroupId(),
                form.getFilterMailinglistId(),
                form.getFilterLanguage(),
                form.getSort(),
                form.getDir(),
                form.getPage(),
                numberOfRows);
    }

    private String saveAdminAndGetView(AdminForm form, Admin admin, Popups popups) {
        final boolean isNew = form.getAdminID() == 0;

        Admin oldSavingAdmin = null;
        AdminPreferences oldSavingAdminPreferences = null;

        if (!isNew) {
            oldSavingAdmin = adminService.getAdmin(form.getAdminID(), form.getCompanyID());
            oldSavingAdminPreferences = adminService.getAdminPreferences(form.getAdminID());
            if (!StringUtils.equals(oldSavingAdmin.getUsername(), form.getUsername())
                    && adminService.checkBlacklistedAdminNames(form.getUsername())) {
                popups.alert(USERNAME_DUPLICATE_MSG);
                return MESSAGES_VIEW;
            }
        } else if (adminService.isGuiAdminLimitReached(form.getCompanyID())) {
            popups.alert("error.admin.limit");
            return MESSAGES_VIEW;
        }
        
        // Set values to persisted values to not overwrite with invalid values
        if(!canEditAltg(admin, form) && oldSavingAdmin != null) {
        	form.setAltgId(oldSavingAdmin.getAccessLimitingTargetGroupID());
        	form.setAltgIds(oldSavingAdmin.getAltgIds());
        }

        final AdminSavingResult result = adminService.saveAdmin(form, false, admin);

        if (!result.isSuccess()) {
            popups.alert(result.getError());
            return MESSAGES_VIEW;
        }

        final Admin savedAdmin = result.getResult();

        if(admin.getAdminID() == savedAdmin.getAdminID()) {
            updateCurrentAdmin(admin, savedAdmin);
        }

		if (isNew) {
			// Log successful creation of new user
			userActivityLogService.writeUserActivityLog(admin, "create user", savedAdmin.getUsername() + " (" + savedAdmin.getAdminID() + ")");
		} else {
			adminChangesLogService.getChangesAsUserActions(form, oldSavingAdmin, oldSavingAdminPreferences)
				.forEach(action -> userActivityLogService.writeUserActivityLog(admin, action));
			
			if (result.isPasswordChanged()) {
				userActivityLogService.writeUserActivityLog(admin, "change password", form.getUsername() + " (" + form.getAdminID() + ")");
			}
		}

        popups.success(CHANGES_SAVED_MSG);

        return "redirect:/admin/" + savedAdmin.getAdminID() + "/view.action";
    }

    protected void loadDataForViewPage(final Admin admin, final Admin adminToEdit, final Model model){
        model.addAttribute("adminGroups", adminGroupService.getAdminGroupsByCompanyIdAndDefault(admin.getCompanyID(), admin, adminToEdit));
        model.addAttribute("layouts", adminService.getEmmLayoutsBase(admin.getCompanyID()));
        model.addAttribute("availableTimeZones", TimeZone.getAvailableIDs());
        model.addAttribute("createdCompanies", adminService.getCreatedCompanies(admin.getCompanyID()));
        addExtendedModelAttr(admin, model);
    }

    protected void addExtendedModelAttr(Admin admin, Model model) {
        // nothing to do
    }

    private void initializeForm(AdminForm form, Admin adminToEdit, Admin editorAdmin) {
        form.setUsername(adminToEdit.getUsername());
        form.setGender(adminToEdit.getGender());
        form.setTitle(adminToEdit.getTitle());
        form.setFullname(adminToEdit.getFullname());
        form.setFirstname(adminToEdit.getFirstName());
        form.setEmployeeID(adminToEdit.getEmployeeID());
        form.setStatEmail(adminToEdit.getStatEmail());
        form.setPassword("");
        form.setAdminPhone(adminToEdit.getAdminPhone());
        form.setPasswordConfirm("");
        form.setCompanyID(adminToEdit.getCompanyID());
        form.setAdminLocale(new Locale(adminToEdit.getAdminLang(), adminToEdit.getAdminCountry()));
        form.setAdminTimezone(adminToEdit.getAdminTimezone());
        form.setGroupIDs(adminToEdit.getGroupIds());
        form.setCompanyName(adminToEdit.getCompanyName());
        form.setEmail(adminToEdit.getEmail());
        form.setLayoutBaseId(adminToEdit.getLayoutBaseID());
        form.setInitialCompanyName(adminToEdit.getInitialCompanyName());
        setExtendedFieldsToForm(form, adminToEdit, editorAdmin);

        form.setAdminPreferences(
                conversionService.convert(
                        adminService.getAdminPreferences(adminToEdit.getAdminID()), AdminPreferences.class)
        );
    }

    protected void setExtendedFieldsToForm(AdminForm form, Admin adminToEdit, Admin editorAdmin) {
        // nothing to do
    }

    private void prepareRightsViewPageData(Admin admin, AdminRightsForm form, Model model, Admin adminToEdit) {
        form.setUsername(adminToEdit.getUsername());
        Map<String, PermissionsOverviewData.PermissionCategoryEntry> permissionsOverview =
                adminService.getPermissionOverviewData(admin, adminToEdit);
        List<PermissionsOverviewData.PermissionCategoryEntry> list = new ArrayList<>(permissionsOverview.values());
        Collections.sort(list);
        model.addAttribute("permissionCategories", list);
    }

    private boolean saveAdminRightsAndWriteToActivityLog(Admin admin, AdminRightsForm aForm, Popups popups) {
        Tuple<List<String>, List<String>> changes = adminService.saveAdminPermissions(admin.getCompanyID(),
                aForm.getAdminID(), aForm.getUserRights(), admin.getAdminID());

        if (changes == null) {
            popups.alert("error.admin.change.permission");
            return false;
        }
        Admin savingAdmin = adminService.getAdmin(aForm.getAdminID(), admin.getCompanyID());

        String action = String.format("User: \"%s\"(%d).", savingAdmin.getUsername(), savingAdmin.getAdminID());
        String added = StringUtils.join(changes.getFirst(), ", ");
        String removed = StringUtils.join(changes.getSecond(), ", ");

        if (!added.isEmpty()) {
            userActivityLogService.writeUserActivityLog(
                    admin, "edit user", action + " Added permissions: " + added, LOGGER);
        }
        if (!removed.isEmpty()) {
            userActivityLogService.writeUserActivityLog(
                    admin, "edit user", action + " Removed permissions: " + removed, LOGGER);
        }
        return true;
    }

    private void updateCurrentAdmin(final Admin currentAdmin, final Admin savedAdmin) {
        currentAdmin.setAdminLang(savedAdmin.getAdminLang());
        currentAdmin.setAdminCountry(savedAdmin.getAdminCountry());
        currentAdmin.setAdminTimezone(savedAdmin.getAdminTimezone());
    }

    protected String prepareErrorPageForNotLoadedAdmin(final int adminId, final int companyID, final Popups popups,
                                                       final String viewName) {
        popups.alert("Error");
        LOGGER.warn("Could not load admin by admin id: {}, company id: {}.", adminId, companyID);
        return viewName;
    }

    @Override
    public boolean isParameterExcludedForUnsafeHtmlTagCheck(Admin admin, String param, String controllerMethodName) {
        return "password".equals(param) || "passwordConfirm".equals(param);
    }
}
