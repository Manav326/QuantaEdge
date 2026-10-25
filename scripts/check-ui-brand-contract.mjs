import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();
const failures = [];
let checks = 0;

function assert(condition, message) {
  checks += 1;
  if (!condition) failures.push(message);
}

function read(path) {
  const file = join(root, path);
  assert(existsSync(file), 'Required UI contract file is missing: ' + path);
  return existsSync(file) ? readFileSync(file, 'utf8') : '';
}

function requireBrandTokens(source, app) {
  assert(/--qe-brand-orange\s*:\s*#f97316\b/i.test(source), app + ' must preserve canonical QuantaEdge orange (#F97316).');
  assert(/--qe-brand-orange-deep\s*:\s*#c2410c\b/i.test(source), app + ' must preserve canonical deep orange (#C2410C).');
}

function requireInteractionBaseline(source, app) {
  assert(/focus-visible/i.test(source), app + ' must retain a visible keyboard focus treatment.');
  assert(/prefers-reduced-motion\s*:\s*reduce/i.test(source), app + ' must honor reduced-motion preferences.');
  assert(/forced-colors\s*:\s*active/i.test(source), app + ' must provide a forced-colors focus fallback.');
  assert(/@media\s*\(\s*max-width\s*:\s*\d+px\s*\)/i.test(source), app + ' must include responsive breakpoint rules.');
}

const branding = read('docs/branding.md');
const brandComponent = read('apps/web/app/components/QuantaEdgeBrand.tsx');
const tenantBrandingProvider = read('apps/web/app/components/TenantBrandingProvider.tsx');
const tenantMetadata = read('apps/web/app/tenantMetadata.ts');
const studentMetadataLayout = read('apps/web/app/student/layout.tsx');
const studentLoginMetadataLayout = read('apps/web/app/login/student/layout.tsx');
const signupMetadataLayout = read('apps/web/app/signup/layout.tsx');
const sessionMetadataLayout = read('apps/web/app/session/layout.tsx');
const learnerMarketplace = read('apps/web/app/student/marketplace/page.tsx');
const schoolEntryPage = read('apps/web/app/school/[institutionCode]/page.tsx');
const schoolEntryLayout = read('apps/web/app/school/[institutionCode]/layout.tsx');
const androidTutorScreen = read('apps/android/app/src/main/java/com/quantaedge/android/ui/TutorScreen.kt');
const androidLiveClasses = read('apps/android/app/src/main/java/com/quantaedge/android/ui/LearningClassesScreen.kt');
const hrmsInstitutionBrandTemplate = read('apps/people/app/institutions/InstitutionBrandTemplate.tsx');
const institutionProfileTests = read('services/api/src/test/java/com/quantaedge/api/InstitutionProfileControllerTest.java');
const hrmsInstitutionPage = read('apps/people/app/institutions/page.tsx');
const androidBuildConfig = read('apps/android/app/build.gradle.kts');
const androidTenantBrand = read('apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeBrand.kt');
const androidTenantRepository = read('apps/android/app/src/main/java/com/quantaedge/android/data/InstitutionBrandingRepository.kt');
const androidAppManifest = read('apps/android/app/src/main/AndroidManifest.xml');
const androidTenantMigration = read('services/api/src/main/resources/db/migration/V121__institution_branding_asset_storage.sql');
const institutionTenantController = read('services/api/src/main/java/com/quantaedge/api/InstitutionTenantController.java');
const institutionPlatformController = read('services/api/src/main/java/com/quantaedge/api/PlatformInstitutionController.java');
const institutionBrandAssetValidator = read('services/api/src/main/java/com/quantaedge/api/InstitutionBrandingAssetValidator.java');
const institutionBrandAssetValidatorTests = read('services/api/src/test/java/com/quantaedge/api/InstitutionBrandingAssetValidatorTest.java');
const institutionBrandAssetControlTests = read('services/api/src/test/java/com/quantaedge/api/InstitutionBrandingAssetControlTest.java');
const authFilter = read('services/api/src/main/java/com/quantaedge/api/AuthFilter.java');
const authFilterTests = read('services/api/src/test/java/com/quantaedge/api/AuthFilterTest.java');
const institutionAuthService = read('services/api/src/main/java/com/quantaedge/api/InstitutionAuthService.java');
const certificateController = read('services/api/src/main/java/com/quantaedge/api/CertificateController.java');
const certificateRenderer = read('services/api/src/main/java/com/quantaedge/api/CertificateRenderer.java');
const androidMainActivity = read('apps/android/app/src/main/java/com/quantaedge/android/MainActivity.kt');
const androidTenantApp = read('apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt');
const androidLocalization = read('apps/android/app/src/main/java/com/quantaedge/android/ui/AndroidLocalization.kt');
const androidCertificateScreen = read('apps/android/app/src/main/java/com/quantaedge/android/ui/StudentCertificatesScreen.kt');
const tutorService = read('services/api/src/main/java/com/quantaedge/api/TutorService.java');
const colorThemeProvider = read('apps/web/app/components/ColorThemeProvider.tsx');
const adminBrandComponent = read('apps/admin/app/components/QuantaEdgeBrand.tsx');
const peopleBrandComponent = read('apps/people/app/components/QuantaEdgeBrand.tsx');
const adminSidebar = read('apps/admin/app/components/AdminSidebar.tsx');
const adminLogin = read('apps/admin/app/login/page.tsx');
const adminWorkspaceLayout = read('apps/admin/app/components/AdminWorkspaceLayout.tsx');
const adminSessionBoundary = read('apps/admin/app/components/AdminSessionBoundary.tsx');
const adminDashboard = read('apps/admin/app/page.tsx');
const peopleWorkspace = read('apps/people/app/components/PeopleWorkspaceLayout.tsx');
const peopleLogin = read('apps/people/app/login/page.tsx');
const peopleChangePassword = read('apps/people/app/change-password/page.tsx');
const webLayout = read('apps/web/app/layout.tsx');
const workspaceLayout = read('apps/web/app/components/WorkspaceLayout.tsx');
const adminLayout = read('apps/admin/app/layout.tsx');
const peopleLayout = read('apps/people/app/layout.tsx');
const webStyles = read('apps/web/app/globals.css');
const adminStyles = read('apps/admin/app/globals.css');
const peopleBaseStyles = read('apps/people/app/globals.css');
const peopleStyles = read('apps/people/app/people-design-system.css');

for (const token of ['#F97316', '#C2410C', '#FFF0E5', 'Smarter Decisions. Greater Growth.']) {
  assert(branding.includes(token), 'Canonical brand documentation must include ' + token + '.');
}
assert(brandComponent.includes('/branding/quantaedge-icon.png'), 'Live QuantaEdge brand component must use the canonical Q-mark asset.');
assert(brandComponent.includes('Smarter Decisions. Greater Growth.'), 'Live QuantaEdge brand component must retain the unchanged tagline.');
assert(adminBrandComponent.includes("const PLATFORM_LOGO_URL = '/branding/quantaedge-logo.svg'") && peopleBrandComponent.includes("const PLATFORM_LOGO_URL = '/branding/quantaedge-logo.svg'"), 'Admin and People Operations must retain the same canonical full-logo asset contract for platform/operator context.');
assert(brandComponent.includes('useTenantBranding') && brandComponent.includes('brand?.displayName') && brandComponent.includes('brand.logoUrl') && brandComponent.includes('brand.appIconUrl') && brandComponent.includes('const logoUrl = brand') && brandComponent.includes('const appIconUrl = brand'), 'Learner web brand lockup must consume resolved tenant branding through distinct full-logo and square-icon roles.');
assert(tenantBrandingProvider.includes('/api/v1/institution/public/branding/') && tenantBrandingProvider.includes('institution/memberships/me'), 'Tenant branding provider must resolve trusted institution codes and a single learner membership.');
assert(!/\.style\.setProperty\(['"]--qe-(?:brand-orange|color-)/.test(tenantBrandingProvider), 'Tenant identity resolution must not write palette variables outside the shared theme system.');
assert(tenantBrandingProvider.includes('SERVER_BRANDING_COOKIES') && tenantBrandingProvider.includes('saveTenantMetadataCookies') && tenantBrandingProvider.includes('qe-tenant-session'), 'Verified school context must be retained in path-scoped server-visible metadata cookies.');
assert(tenantBrandingProvider.includes('const matchingCookie = SERVER_BRANDING_COOKIES.find') && tenantBrandingProvider.includes('document.cookie.split'), 'Client tenant identity must recover from path-scoped metadata cookies when sessionStorage is blocked or stale.');
assert(tenantMetadata.includes('await cookies()') && tenantMetadata.includes('neutralSchoolMetadata') && tenantMetadata.includes('/api/v1/institution/public/branding/'), 'Learner metadata must resolve the public school identity server-side and avoid platform-brand leakage on invalid tenant metadata.');
assert(studentMetadataLayout.includes("learnerMetadataForTenantCookie('qe-tenant-student')") && studentLoginMetadataLayout.includes("learnerMetadataForTenantCookie('qe-tenant-login-student')") && signupMetadataLayout.includes("learnerMetadataForTenantCookie('qe-tenant-signup')") && sessionMetadataLayout.includes("learnerMetadataForTenantCookie('qe-tenant-session')"), 'Learner workspace, sign-in, signup and session routes must carry server-rendered school metadata.');
assert(learnerMarketplace.includes('name: brand?.displayName || \'QuantaEdge\'') && learnerMarketplace.includes('institutionContextRequested && (!brand || isResolving)'), 'Learner checkout must use the validated school display name and must fail closed if school identity is unresolved.');
assert(tenantBrandingProvider.includes("setStoredEnrollmentIssue('invalid')") && tenantBrandingProvider.includes("setStoredEnrollmentIssue('rate-limited')") && tenantBrandingProvider.includes("setStoredEnrollmentIssue('temporary')") && tenantBrandingProvider.includes('ENROLLMENT_ISSUE_SESSION_KEY'), 'Learner enrollment failures must remain visible across reloads within the current browser tab.');
assert(tenantBrandingProvider.includes('retryEnrollment') && /\[\s*pathname\s*,\s*enrollmentRetryVersion(?:\s*,|\s*\])/.test(tenantBrandingProvider) && workspaceLayout.includes('retryEnrollment') && workspaceLayout.includes('qe-enrollment-issue-retry'), 'Transient or rate-limited learner invitations must have a safe retry action.');
assert(androidTenantApp.includes('institutionEnrollmentMessage') && androidTenantApp.includes('institutionEnrollmentRetryable') && androidTenantApp.includes('institutionJoinFailure'), 'Android must expose a visible school enrollment failure state with safe retry and context-isolation behavior.');
assert(androidAppManifest.includes('${institutionLinkScheme}') && androidAppManifest.includes('${institutionLinkPath}') && androidBuildConfig.includes('val institutionLinkScheme') && androidTenantBrand.includes('LocalInstitutionBrandIdentity'), 'Android tenant identity and generated tenant-specific invitation routing must remain wired to the school context.');
assert(androidAppManifest.includes('android:autoVerify="${institutionAutoVerify}"') && androidBuildConfig.includes('val institutionAutoVerify = configuredInstitutionCode.isNotBlank().toString()'), 'Tenant packages must request HTTPS App Link verification; association-file deployment is a separate release gate.');
assert(androidBuildConfig.includes('val tenantPackageSlug = "school" +'), 'Generated tenant package slugs must start with a letter even when institution codes start with digits.');
assert(workspaceLayout.includes('qe-enrollment-issue'), 'Failed learner invitations must produce accessible, actionable enrollment feedback.');
assert(certificateController.includes('More than one school can issue this certificate') && certificateController.includes('issuers.size() > 1'), 'Certificate issuer selection must reject ambiguous multi-school attribution instead of silently switching to the platform issuer.');
assert(!workspaceLayout.includes('/student/institutions') && !workspaceLayout.includes('mySchool'), 'Learner navigation must not expose a school/institution selector; school identity is implicit in the branded app.');
assert(androidTenantBrand.includes('LocalInstitutionBrandIdentity') && androidTenantBrand.includes('institution.displayName'), 'Android shared brand lockup must render a resolved institution name.');
assert(androidTenantRepository.includes('/api/v1/institution/public/branding/') && androidTenantRepository.includes('/api/v1/institution/join'), 'Android must resolve public school branding and use the membership join API.');
assert(tenantBrandingProvider.includes('tenantCodeFromSchoolPath') && tenantBrandingProvider.includes('JOIN_TENANT_SESSION_KEY'), 'Web branding must bind invitation context to the school-specific path across sign-in redirects.');
assert(tenantBrandingProvider.includes('window.location.hash') && tenantBrandingProvider.includes('history.replaceState'), 'Invitation codes must travel in URL fragments and be removed from browser history after safe capture.');
assert(institutionTenantController.includes('value(body,"institutionCode")') && institutionAuthService.includes("and (?='' or t.tenant_code=?)"), 'Server redemption must bind a join code to the expected school when the link specifies one.');
assert(institutionTenantController.includes('RequestParam(value="institutionCode"') && institutionTenantController.includes('requiresInstitutionContext'), 'The learner membership endpoint must scope to a school and avoid multi-school disclosure.');
assert(androidCertificateScreen.includes('institutionBrand?.institutionCode?.let { body.put("institutionCode", it) }'), 'Android certificate issuance must preserve the current school issuer context.');
assert(tutorService.includes('You are a helpful tutor for school learners') && !tutorService.includes('You are QuantaEdge Tutor for school learners'), 'Generated tutor answers must not identify QuantaEdge as the learner-facing tutor.');
assert(tutorService.includes('learnerTutorIdentityLabel') && tutorService.includes('Do not mention QuantaEdge or the underlying software provider') && tutorService.includes('currentTutorMetaAnswer(responseLanguage,normalized,null,tutorIdentityLabel)'), 'Tutor identity and direct identity answers must follow the active school context instead of revealing the underlying provider.');
assert(tutorService.includes('suppressUnderlyingProviderName(reply.text(),responseLanguage,tutorIdentityLabel)'), 'Generated tutor replies must be screened for provider-name leakage in school context.');
assert(androidCertificateScreen.includes('institutionBrandResolving') && androidCertificateScreen.includes('certificateFileStem') && androidCertificateScreen.includes('school-certificate'), 'Android certificate copy and exported filenames must stay neutral while institution identity is resolving.');
assert(androidTenantApp.includes('remove("institutionDisplayName")') && androidTenantApp.includes('remove("institutionBrandingVersion")'), 'Android must clear cached school identity on logout, rejection and tenant switches.');
assert(androidTutorScreen.includes('contentDescription = institutionBrand?.displayName ?: if (institutionBrandResolving)'), 'Android tutor accessibility labels must not expose the provider name in school context.');
assert(colorThemeProvider.includes('brand?.themeTokens') && colorThemeProvider.includes('applyPalette(actual, tokens)'), 'Tenant identity must continue to feed the existing shared v2 theme provider without a parallel palette system.');
assert(tenantBrandingProvider.includes('window.location.hash') && tenantBrandingProvider.includes('history.replaceState'), 'Invitation secrets must be captured from a URL fragment and removed from browser history.');
assert(hrmsInstitutionPage.includes('url.hash=new URLSearchParams({join:joinCode}).toString()'), 'HRMS school invitations must keep private join tokens in URL fragments, not query strings.');
assert(institutionAuthService.includes('String expectedTenantCode') && institutionAuthService.includes("and (?='' or t.tenant_code=?)") && institutionTenantController.includes('value(body,"institutionCode")'), 'Backend invitation redemption must bind membership to the tenant in the school link.');
assert(certificateController.includes('resolveCertificateIssuer') && certificateController.includes('CertificateRenderer.CertificateBranding') && certificateRenderer.includes('institutionIssued'), 'Certificates must use the active school issuer name and approved logo when the learner has a matching membership.');
assert(androidMainActivity.includes('institutionDisplayName') && androidMainActivity.includes('BuildConfig.INSTITUTION_DISPLAY_NAME') && androidMainActivity.includes('institutionBrandingVersion'), 'Learner checkout must use only the verified current school identity and clear stale cached display names on switching.');
assert(androidLocalization.includes('LocalInstitutionBrandResolutionPending') && androidLocalization.includes('replace("QuantaEdge", name)'), 'Android learner copy must suppress the provider name while a school identity is resolving and after it resolves.');
assert(androidBuildConfig.includes('A tenant-branded Android package requires QUANTAEDGE_INSTITUTION_ICON_PATH') && androidBuildConfig.includes('configuredInstitutionApplicationId'), 'A tenant-specific Android release must require a school launcher image and separate package ID.');
assert(schoolEntryPage.includes('brand.displayName') && schoolEntryPage.includes('/login/student') && schoolEntryPage.includes('/signup'), 'A direct school invitation must show the school brand before learner authentication.');
assert(!schoolEntryPage.includes('QuantaEdgeBrand') && !schoolEntryPage.includes('qe-school-entry-header'), 'The school entry page must avoid duplicating the school logo/name lockup.');
assert(schoolEntryPage.includes('resolveInstitutionCode(params.institutionCode)') && schoolEntryPage.includes('Try again'), 'A temporarily unavailable school brand must provide a retry action instead of a dead-end screen.');
assert(schoolEntryLayout.includes('generateMetadata') && schoolEntryLayout.includes('title: { absolute: name }') && schoolEntryLayout.includes('/api/v1/institution/public/branding/'), 'The browser school entry must server-render school-specific title and icon metadata without inheriting platform branding.');
assert(androidTutorScreen.includes('LocalInstitutionBrandIdentity.current') && androidTutorScreen.includes('institutionBrand?.logo') && androidTutorScreen.includes('institutionBrandResolving'), 'The floating Android tutor must show the school mark or a neutral resolving mark rather than the platform Q-mark in tenant context.');
assert(androidLiveClasses.includes('val teacherFallback = when') && androidLiveClasses.includes('LocalInstitutionBrandIdentity.current'), 'Live-class host fallback copy must use the current school identity when available.');
assert(androidLocalization.includes('Ask your school administrator to create more accounts.') && androidLocalization.includes('अपने विद्यालय प्रशासक से संपर्क करें'), 'School-branded help copy must direct learners to their school, not the underlying platform provider.');
assert(hrmsInstitutionPage.includes('schoolLearningLink') && hrmsInstitutionPage.includes("new URL('/school/" ) && hrmsInstitutionPage.includes('Copy school learning link'), 'HRMS must generate a shareable school-branded invite URL rather than only exposing the raw join code.');
assert(androidAppManifest.includes('${institutionLinkPath}') && androidAppManifest.includes('${applicationLabel}') && androidAppManifest.includes('${applicationIcon}'), 'Android launcher identity and app-link route must be configured per branded build.');
assert(androidBuildConfig.includes('configuredInstitutionApplicationId') && androidBuildConfig.includes('configuredInstitutionIconPath') && androidBuildConfig.includes('institutionLinkPath'), 'Android per-school builds must use a unique package ID, supplied school icon and school-scoped link path.');
assert(androidTenantMigration.includes('CREATE TABLE institution_branding_asset') && androidTenantMigration.includes('image_bytes'), 'Institution branding image storage must be tenant-scoped and bounded.');
assert(institutionTenantController.includes('/assets/{assetType}') && institutionTenantController.includes('assetUrl(normalized'), 'The API must serve verified logo and app-icon assets only through the resolved tenant.');
assert(institutionPlatformController.includes('uploadBrandingAsset') && institutionPlatformController.includes('removeBrandingAsset') && institutionPlatformController.includes('Use the validated branding asset upload or remove endpoint'), 'Only the validated tenant asset lifecycle may change selected logo and icon references.');
assert(institutionBrandAssetValidator.includes('MAX_BYTES = 2 * 1024 * 1024') && institutionBrandAssetValidator.includes('ImageIO.getImageReaders') && institutionBrandAssetValidator.includes('containsAnimatedPngControlChunk'), 'Branding uploads must enforce size, static raster-format and decoder validation on the server.');
assert(institutionBrandAssetValidatorTests.includes('rejectsOversizedUploadsBeforeImageDecoding') && institutionBrandAssetValidatorTests.includes('rejectsImagePixelDimensionsAboveSafetyLimit') && institutionBrandAssetValidatorTests.includes('rejectsAnimatedPngControlChunks'), 'Asset validator tests must cover byte, pixel and animation safety limits.');
assert(institutionPlatformController.includes('@DeleteMapping("/{institutionId}/branding/assets/{assetType}")') && institutionPlatformController.includes('BRANDING_ASSET_REMOVED'), 'The platform owner must be able to remove a currently selected logo or app icon with an audit record.');
assert(institutionTenantController.includes('b.version=?') && institutionTenantController.includes('matchesEntityTag') && institutionTenantController.includes('IF_NONE_MATCH'), 'Public tenant assets must require the current branding version and support conditional cache revalidation.');
assert(institutionBrandAssetControlTests.includes('ownerCanRemoveAnAsset') && institutionBrandAssetControlTests.includes('assetRemovalRejectsNonOwner') && institutionBrandAssetControlTests.includes('genericBrandingSaveCannotBypassValidatedAssetUploadAndRemoval') && institutionBrandAssetControlTests.includes('staleVersionDoesNotResolve'), 'Asset control tests must cover owner access, removal and stale URL rejection.');
assert(authFilter.includes('MAX_BRANDING_ASSET_REQUEST_BYTES') && authFilter.includes('authorization.hasPermission(context, "QE_PLATFORM_OWNER")'), 'The API filter must cap multipart branding uploads before parsing and require owner permission.');
assert(authFilterTests.includes('oversizedBrandingUploadIsRejectedBeforeMultipartParsing') && authFilterTests.includes('anonymousBrandingUploadIsRejectedBeforeMultipartParsing'), 'Upload guard tests must cover oversized and anonymous requests before multipart parsing.');

assert(hrmsInstitutionPage.includes('InstitutionBrandTemplate') && !hrmsInstitutionPage.includes('pendingCrop'), 'HRMS institution settings must use the centralized brand template instead of a square crop-upload path.');
assert(hrmsInstitutionBrandTemplate.includes('logo.png') && hrmsInstitutionBrandTemplate.includes('icon-192.png') && hrmsInstitutionBrandTemplate.includes('maskable-icon-512.png') && hrmsInstitutionBrandTemplate.includes('favicon-16.png') && hrmsInstitutionBrandTemplate.includes('site.webmanifest') && hrmsInstitutionBrandTemplate.includes('createZip'), 'The brand template must export proportion-preserving logo renditions, app icons, favicon sizes, a web manifest and source artwork as a ZIP.');
assert(hrmsInstitutionBrandTemplate.includes('/branding/template-assets') && hrmsInstitutionBrandTemplate.includes('Apply pack to this institution') && hrmsInstitutionBrandTemplate.includes('Download ZIP folder'), 'The generated pack must be downloadable or atomically applied to the selected HRMS institution.');
assert(institutionPlatformController.includes('/branding/template-assets') && institutionPlatformController.includes('normalizeBrandTagline') && institutionPlatformController.includes('BRANDING_TEMPLATE_APPLIED'), 'The HRMS asset-template API must validate the name/tagline and atomically audit both validated assets.');
assert(institutionTenantController.includes('result.put("tagline"') && institutionPlatformController.includes('b.tagline'), 'Institution tagline must be available in public learner branding and HRMS settings.');
assert(tenantBrandingProvider.includes("brand?.appIconUrl") && !tenantBrandingProvider.includes("brand?.logoUrl && approvedAssetUrl(brand.logoUrl, brand.institutionCode, 'logo')\n      ? brand.logoUrl"), 'Browser favicons must use the square app-icon URL, never a wide logo URL.');
assert(brandComponent.includes('PLATFORM_LOGO_URL') && brandComponent.includes('qe-brand__logo') && brandComponent.includes('qe-brand__tenant-icon') && brandComponent.includes("'app-icon'"), 'The learner brand renderer must use a full-logo slot and a distinct square icon slot for platform and tenant identity.');
assert(adminBrandComponent.includes('quantaedge-logo.svg') && peopleBrandComponent.includes('quantaedge-logo.svg'), 'QuantaEdge platform branding must keep the same full-logo URL contract in Admin and HRMS.');
assert(hrmsInstitutionPage.includes("editingCard==='profile'") && hrmsInstitutionPage.includes("editingCard==='branding'") && hrmsInstitutionPage.includes("editingCard==='billing'") && hrmsInstitutionPage.includes('Save profile') && hrmsInstitutionPage.includes('Save contract') && hrmsInstitutionPage.includes('Cancel') && hrmsInstitutionPage.includes('<h3>Courses</h3>') && hrmsInstitutionPage.includes("onClick={()=>setTab('marketplace')}") && !hrmsInstitutionPage.includes("editingCard==='courses'") && !hrmsInstitutionPage.includes('saveCourseAuthorisation'), 'Institution settings must stay read-only until their individual Edit action; course access must be release-scoped and managed through the Marketplace launch workflow.');
assert(institutionPlatformController.includes('@PutMapping("/{institutionId}/profile")') && institutionPlatformController.includes('INSTITUTION_PROFILE_CHANGED') && institutionPlatformController.includes('defaultBrandNameSynchronized'), 'Institution profile updates must be owner-only, audited, and preserve separately customized white-label identity.');
assert(institutionProfileTests.includes('ownerCanUpdateProfileAndKeepDefaultBrandNameInSync') && institutionProfileTests.includes('profileUpdateRejectsNonOwnerBeforeReadingInstitutionData') && institutionProfileTests.includes('profileUpdateRejectsInvalidDisplayNameBeforeDatabaseWrites') && institutionProfileTests.includes('profileUpdateDoesNotOverwriteACustomizedBrandDisplayName'), 'Institution profile API tests must cover successful audit path, owner authorization, and validation.');assert(hrmsInstitutionPage.includes('onRemoveAsset={removeBrandAsset}') && hrmsInstitutionPage.includes("{method:'DELETE'}") && hrmsInstitutionBrandTemplate.includes("removeAsset('logo')") && hrmsInstitutionBrandTemplate.includes("removeAsset('app-icon')"), 'The HRMS owner UI must retain working remove controls for both branding assets in the standardized template.');
assert(adminSidebar.includes("import QuantaEdgeBrand from './QuantaEdgeBrand'"), 'Admin sidebar must use the canonical shared brand component.');
assert(adminWorkspaceLayout.includes("import QuantaEdgeBrand from './QuantaEdgeBrand'"), 'Admin mobile workspace header must use the shared brand component.');
assert(adminWorkspaceLayout.includes('className="qe-admin-global-brand"'), 'Admin mobile workspace header must render the canonical brand lockup.');
assert(peopleWorkspace.includes('className="qe-people-mobile-brand-lockup"'), 'People mobile workspace header must render the canonical brand lockup.');
assert(!peopleWorkspace.includes('<span>QUANTAEDGE</span>'), 'People workspace must not hard-code a duplicate brand name outside the shared component.');
assert(peopleWorkspace.includes("import QuantaEdgeBrand from './QuantaEdgeBrand'"), 'People workspace must use the canonical shared brand component.');
assert(!adminSidebar.includes('quantaedge-wordmark.png'), 'Admin sidebar must not render the invalid legacy wordmark PNG.');
assert(adminLogin.includes("import QuantaEdgeBrand from '../components/QuantaEdgeBrand'"), 'Admin login must use the canonical shared brand component.');
assert(adminLogin.includes('<QuantaEdgeBrand href="/" variant="auth"') && adminLogin.includes('<QuantaEdgeBrand href="/" variant="compact"'), 'Admin login must use the canonical lockup on desktop and mobile.');
assert(!adminLogin.includes('quantaedge-wordmark.png'), 'Admin login must not render the invalid legacy wordmark PNG.');
assert(!peopleWorkspace.includes('quantaedge-wordmark.png') && !peopleLogin.includes('quantaedge-wordmark.png') && !peopleChangePassword.includes('quantaedge-wordmark.png'), 'People workspace and authentication must not render the invalid legacy wordmark PNG.');
assert(peopleLogin.includes('<QuantaEdgeBrand href={null} variant="auth"') && peopleChangePassword.includes('<QuantaEdgeBrand href={null} variant="auth"'), 'HRMS login and password-change screens must use the shared brand lockup.');
assert(webLayout.includes('/branding/quantaedge-icon.png'), 'Learner web must retain the QuantaEdge fallback browser icon.');
assert(webLayout.includes('/branding/quantaedge-logo.svg') === false, 'The document metadata must continue to point at the square icon, not the full logo asset.');
assert(webLayout.includes('TenantBrandingProvider'), 'Learner web must mount the tenant branding provider globally.');
assert(adminLayout.includes('/branding/quantaedge-icon.png'), 'Admin must configure the canonical browser/app icon.');
assert(peopleLayout.includes('/branding/quantaedge-icon.png'), 'People Operations must configure the canonical browser/app icon.');
requireBrandTokens(webStyles, 'Learner web');
requireBrandTokens(adminStyles, 'Admin');
assert(/--brand\s*:\s*#f97316\b/i.test(peopleStyles), 'People Operations must use canonical orange as its primary brand token.');
assert(/--brand2\s*:\s*#c2410c\b/i.test(peopleStyles), 'People Operations must use canonical deep orange for its strong brand token.');
assert(/--brand-soft\s*:\s*#fff0e5\b/i.test(peopleStyles), 'People Operations must use the canonical soft-orange surface.');
requireInteractionBaseline(webStyles, 'Learner web');
requireInteractionBaseline(adminStyles, 'Admin');
requireInteractionBaseline(peopleStyles, 'People Operations');
assert(/--qe-answer-incorrect-text\s*:\s*#b42318\b/i.test(webStyles), 'Learner web must retain distinct incorrect-answer feedback styling.');
assert(/--qe-answer-incorrect-text\s*:\s*#b42318\b/i.test(adminStyles), 'Admin preview must retain distinct incorrect-answer feedback styling.');
assert(/\.practice-feedback-incorrect/.test(webStyles), 'Practice UI must retain the explicit incorrect-answer feedback treatment.');
assert(/\.qe-people-status--success/.test(peopleStyles) && /\.qe-people-status--danger/.test(peopleStyles), 'People Operations must retain distinct success and error status variants.');
assert(/overscroll-behavior-inline:\s*contain/.test(webStyles), 'Learner surfaces must protect horizontal scroll regions on touch devices.');
assert(/overscroll-behavior-inline:\s*contain/.test(adminStyles), 'Admin data/preview surfaces must protect horizontal scroll regions.');
assert(webStyles.includes('.qe-workspace-layout') && webStyles.includes('.qe-structured-student-page') && webStyles.includes('.qe-practice-page'), 'Learner responsive polish must target actual learner workspace, lesson and practice markup.');
assert(adminStyles.includes('.qe-editor-actions') && adminStyles.includes('.qe-readiness-summary') && adminStyles.includes('@media (max-width: 720px)'), 'Admin responsive polish must cover Content Studio authoring actions and small-screen grids.');
assert(/tbody tr:focus-within/.test(peopleStyles), 'People tables must keep keyboard focus within rows discoverable.');
assert(!/(#244c9a|#17376f|#10264b|#3569c8|#6e94d6)/i.test(peopleBaseStyles + peopleStyles), 'People Operations must not regress to its former blue palette.');

const brandAssetPaths = [
  'apps/web/public/branding/quantaedge-icon.png',
  'apps/admin/public/branding/quantaedge-icon.png',
  'apps/people/public/branding/quantaedge-icon.png',
];
const brandAssets = brandAssetPaths.map(path => {
  const absolute = join(root, path);
  assert(existsSync(absolute) && statSync(absolute).size > 0, 'Missing packaged canonical brand asset: ' + path);
  return existsSync(absolute) ? readFileSync(absolute) : Buffer.alloc(0);
});
if (brandAssets.every(asset => asset.length > 0)) {
  assert(brandAssets[0].equals(brandAssets[1]) && brandAssets[0].equals(brandAssets[2]), 'All app contexts must package a byte-identical canonical Q-mark asset.');
}

const logoAssetPaths = [
  'apps/web/public/branding/quantaedge-logo.svg',
  'apps/admin/public/branding/quantaedge-logo.svg',
  'apps/people/public/branding/quantaedge-logo.svg',
];
const logoAssets = logoAssetPaths.map(path => {
  const absolute = join(root, path);
  assert(existsSync(absolute) && statSync(absolute).size > 0, 'Missing shared full-logo asset: ' + path);
  return existsSync(absolute) ? readFileSync(absolute) : Buffer.alloc(0);
});
if (logoAssets.every(asset => asset.length > 0)) {
  assert(logoAssets[0].equals(logoAssets[1]) && logoAssets[0].equals(logoAssets[2]), 'All app contexts must package a byte-identical canonical full-logo asset.');
}

const institutionThemeTokens = read('services/api/src/main/java/com/quantaedge/api/InstitutionThemeTokens.java');
assert(institutionPlatformController.includes('InstitutionThemeTokens.normalize(raw)') && institutionThemeTokens.includes('value.matches("^#[0-9A-Fa-f]{6}$")'), 'Tenant theme values must be restricted to valid six-digit hex colours.');

const adminThemeProvider = read('apps/admin/app/components/AdminThemeProvider.tsx');
const adminThemeStyles = read('apps/admin/app/admin-theme.css');
assert(adminWorkspaceLayout.includes('AdminThemeProvider') && adminWorkspaceLayout.includes('AdminAppearanceModeButton'), 'The shared Admin workspace must mount tenant appearance and expose its theme control on every authenticated route.');
assert(adminWorkspaceLayout.includes("institutionCode={institutionCode}") && adminWorkspaceLayout.includes("/api/v1/admin/content/institution-context"), 'Admin appearance must follow the server-resolved HRMS institution binding; browser-selected tenant fallbacks are forbidden.');
assert(adminLayout.includes("import './admin-theme.css'"), 'Admin theme overrides must load globally from the root layout, rather than only on selected routes.');
assert(adminThemeProvider.includes('qe.appearance-mode.v1') && adminThemeProvider.includes("'institution', 'system', 'dark', 'light'"), 'Admin and learner web must share the same saved appearance preference and four-mode cycle.');
assert(adminThemeProvider.includes('themeTokens') && adminThemeProvider.includes('applyPalette(actual, tokens)') && adminThemeProvider.includes('root.dataset.qeAppearance = actual'), 'Admin must consume the institution palette through the same semantic theme-token roles as learner web.');
assert(adminThemeProvider.includes('/api/v1/institution/public/branding/') && adminThemeProvider.includes("approvedAssetUrl(body.logoUrl, code, 'logo')") && adminThemeProvider.includes("approvedAssetUrl(body.appIconUrl, code, 'app-icon')"), 'Tenant brand URLs in Admin must be validated and bound to the active institution code.');
assert(adminBrandComponent.includes('brand?.logoUrl') && adminBrandComponent.includes('brand?.appIconUrl') && adminBrandComponent.includes('institutionCode'), 'Tenant Admin headers and sidebar must use the active institution full logo and square app icon, not a fixed platform logo.');
assert(adminThemeStyles.includes('html[data-qe-appearance="dark"]') && adminThemeStyles.includes('.qe-admin-workspace-page-slot') && adminThemeStyles.includes('button[class*="primaryButton"]') && adminThemeStyles.includes(':focus-visible'), 'Shared Admin theme styles must cover dark surfaces, route content, tenant primary actions and visible keyboard focus.');
assert(adminThemeStyles.includes('[class*="chip"]') && adminThemeStyles.includes('[class*="Chip"]') && adminThemeStyles.includes('[style*="#fff" i]') && adminThemeStyles.includes('[style*="255, 255, 255" i]'), 'Dark Admin theme must normalize chips and override common hard-coded light surfaces across routes.');
assert(adminSessionBoundary.includes('Keep the authenticated shell mounted during App Router navigation') && !adminSessionBoundary.includes('setReady(false)'), 'Admin session verification must not unmount the workspace shell on every route change.');
assert(adminWorkspaceLayout.includes("key={pathname + '|' + institutionCode}") && !adminWorkspaceLayout.includes('window.location.reload()'), 'Admin route and institution changes must update only the page slot, not reload the whole browser.');
assert(!adminWorkspaceLayout.includes('router.refresh()'), 'Admin logout navigation must not trigger a second full route refresh.');
assert(!adminDashboard.includes('window.location.reload()') && adminDashboard.includes('reloadVersion') && adminDashboard.includes('void reloadCourses()'), 'Admin dashboard refresh and retry must refetch data through component state rather than reload the page.');
assert(adminThemeStyles.includes('qe-admin-route-enter') && adminThemeStyles.includes('prefers-reduced-motion: reduce'), 'Admin route transitions must be subtle and disabled when reduced motion is preferred.');
assert(adminThemeStyles.includes('.qe-nav-icon') && adminThemeStyles.includes('background: transparent !important') && adminThemeStyles.includes('.admin-visual-card'), 'Admin navigation icons must not use distracting tiles and the dark overview must not retain near-white illustration cards.');
assert(adminThemeStyles.includes('[class*="Heading"]') && adminThemeStyles.includes('color: var(--qe-color-text) !important'), 'Dark Admin theme must maintain readable headings and labels for curriculum, chapters and topics.');
assert(adminThemeProvider.includes("link[rel~='icon']") || adminThemeProvider.includes('link[rel~="icon"]'), 'Admin must dynamically bind browser favicon rendering to the validated HRMS icon.');
assert(adminThemeProvider.includes("brand?.appIconUrl || '/favicon.svg'") && adminThemeProvider.includes('link[rel="apple-touch-icon"]'), 'Admin browser and Apple icons must use the HRMS square app icon with a self-contained fallback.');
assert(adminThemeProvider.includes("'--qe-panel': palette.surface") && adminThemeStyles.includes('.qe-admin-marketplace-route') && adminThemeStyles.includes('[class*="previewBar"]') && adminThemeStyles.includes('[class*="rowSelected"]'), 'Admin must theme the legacy source-ingestion alias, Marketplace hero exception, and bespoke preview/translation surfaces.');
assert(read('apps/admin/app/source-ingestion/page.tsx').includes('qe-admin-source-ingestion-route') && read('apps/admin/app/translations/page.tsx').includes('qe-admin-translations-route') && adminThemeStyles.includes('.qe-structured-submit'), 'Source Ingestion, Translations, and structured-lesson preview must retain explicit theme scopes and controls.');

function findAdminPageFiles(directory) {
  const absoluteDirectory = join(root, directory);
  if (!existsSync(absoluteDirectory)) return [];
  return readdirSync(absoluteDirectory, { withFileTypes: true }).flatMap(entry => {
    const childPath = directory + '/' + entry.name;
    if (entry.isDirectory()) return findAdminPageFiles(childPath);
    return /^page\.(?:tsx|ts|jsx|js)$/.test(entry.name) ? [childPath] : [];
  });
}

const adminRoutePages = findAdminPageFiles('apps/admin/app').sort();
assert(adminRoutePages.includes('apps/admin/app/page.tsx'), 'The Admin overview route must be part of the route-wide theme audit.');
assert(adminRoutePages.includes('apps/admin/app/login/page.tsx'), 'The Admin public sign-in route must be part of the route-wide theme audit.');
for (const path of adminRoutePages) {
  const page = read(path);
  assert(/export default/.test(page), 'Admin route ' + path.replace(/^apps\/admin\/app/, '') + ' must retain its route component while inheriting the shared theme shell.');
}

if (failures.length) {
  console.error('QuantaEdge cross-app UI/brand contract failed (' + failures.length + '/' + checks + ' checks):');
  for (const failure of failures) console.error('- ' + failure);
  process.exitCode = 1;
} else {
  console.log('QuantaEdge cross-app UI/brand contract passed: ' + checks + ' checks across learner web, Admin, People Operations, and official brand assets.');
}
