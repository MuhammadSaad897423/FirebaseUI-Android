package com.firebase.ui.auth.viewmodel;

import android.app.Application;
import android.os.Looper;

import com.firebase.ui.auth.FirebaseAuthAnonymousUpgradeException;
import com.firebase.ui.auth.IdpResponse;
import com.firebase.ui.auth.data.model.FlowParameters;
import com.firebase.ui.auth.data.model.Resource;
import com.firebase.ui.auth.data.model.User;
import com.firebase.ui.auth.testhelpers.AutoCompleteTask;
import com.firebase.ui.auth.testhelpers.AutoContinueTask;
import com.firebase.ui.auth.testhelpers.FakeAuthResult;
import com.firebase.ui.auth.testhelpers.ResourceMatchers;
import com.firebase.ui.auth.testhelpers.TestConstants;
import com.firebase.ui.auth.testhelpers.TestHelper;
import com.firebase.ui.auth.ui.HelperActivityBase;
import com.firebase.ui.auth.util.data.AuthOperationManager;
import com.firebase.ui.auth.util.data.ProviderUtils;
import com.firebase.ui.auth.viewmodel.idp.LinkingSocialProviderResponseHandler;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FacebookAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthCredential;
import com.google.firebase.auth.GoogleAuthProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.util.Collections;

import androidx.lifecycle.Observer;
import androidx.test.core.app.ApplicationProvider;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * Unit tests for {@link LinkingSocialProviderResponseHandler}.
 * <p>
 * This handler is used by WelcomeBackIdpPrompt. This prompt handles the case where a user attempts
 * to log in with an IDP, but there is a user that has a different IDP with the same email.
 * <p>
 * In this case, the handler will link the provider to the existing firebase user. When the user
 * is anonymous, a triple linking case occurs.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class LinkingSocialProviderResponseHandlerTest {

    private static final String MICROSOFT_PROVIDER = "microsoft.com";
    private static final String DISPLAY_NAME = "displayName";

    @Mock FirebaseAuth mMockAuth;
    @Mock FirebaseAuth mScratchMockAuth;

    @Mock FirebaseUser mMockUser;
    @Mock Observer<Resource<IdpResponse>> mResponseObserver;

    private LinkingSocialProviderResponseHandler mHandler;


    @Before
    public void setUp() {
        TestHelper.initialize();
        MockitoAnnotations.initMocks(this);

        mHandler = new LinkingSocialProviderResponseHandler((Application) ApplicationProvider.getApplicationContext());
        FlowParameters testParams = TestHelper.getFlowParameters(Collections.singletonList(
                GoogleAuthProvider.PROVIDER_ID));
        mHandler.initializeForTesting(testParams, mMockAuth, null);
    }

    @Test
    public void testSignIn_withSameIdp_expectSuccess() {
        mHandler.getOperation().observeForever(mResponseObserver);

        // Fake social response from Google
        IdpResponse response = new IdpResponse.Builder(new User.Builder(
                GoogleAuthProvider.PROVIDER_ID, TestConstants.EMAIL).build())
                .setToken(TestConstants.TOKEN)
                .build();

        when(mMockAuth.signInWithCredential(any(AuthCredential.class)))
                .thenReturn(AutoCompleteTask.forSuccess(FakeAuthResult.INSTANCE));

        mHandler.startSignIn(response);
        shadowOf(Looper.getMainLooper()).idle();

        verify(mMockAuth).signInWithCredential(any(GoogleAuthCredential.class));

        InOrder inOrder = inOrder(mResponseObserver);
        inOrder.verify(mResponseObserver)
                .onChanged(argThat(ResourceMatchers.isLoading()));
        inOrder.verify(mResponseObserver)
                .onChanged(argThat(ResourceMatchers.isSuccess()));
    }


    @Test
    public void testSignIn_anonymousUpgradeEnabledWithSameIdp_expectMergeFailure() {
        mHandler.getOperation().observeForever(mResponseObserver);
        setupAnonymousUpgrade();

        // Fake social response from Google
        IdpResponse response = new IdpResponse.Builder(new User.Builder(
                GoogleAuthProvider.PROVIDER_ID, TestConstants.EMAIL).build())
                .setToken(TestConstants.TOKEN)
                .build();

        mHandler.startSignIn(response);

        // Since we are signing in with the same IDP and anonymous upgrade is enabled, a merge
        // failure should occur without any RPC calls

        AuthCredential credential = GoogleAuthProvider.getCredential(TestConstants.TOKEN, null);

        InOrder inOrder = inOrder(mResponseObserver);
        inOrder.verify(mResponseObserver)
                .onChanged(argThat(ResourceMatchers.isLoading()));

        ArgumentCaptor<Resource<IdpResponse>> resolveCaptor =
                ArgumentCaptor.forClass(Resource.class);
        inOrder.verify(mResponseObserver).onChanged(resolveCaptor.capture());

        FirebaseAuthAnonymousUpgradeException e =
                (FirebaseAuthAnonymousUpgradeException) resolveCaptor.getValue().getException();

        GoogleAuthCredential responseCredential =
                (GoogleAuthCredential) e.getResponse().getCredentialForLinking();

        assertThat(responseCredential.getProvider()).isEqualTo(credential.getProvider());
        assertThat(responseCredential.getSignInMethod()).isEqualTo(credential.getSignInMethod());

    }

    @Test
    public void testSignIn_withDifferentIdp_expectSuccess() {
        mHandler.getOperation().observeForever(mResponseObserver);

        // We're going to fake a sign in with facebook, where the email belongs
        // to an existing account with a Google provider.

        // Fake social response from Google
        IdpResponse response = new IdpResponse.Builder(new User.Builder(
                GoogleAuthProvider.PROVIDER_ID, TestConstants.EMAIL).build())
                .setToken(TestConstants.TOKEN)
                .build();

        // Set facebook credential
        AuthCredential facebookAuthCredential =
                FacebookAuthProvider.getCredential(TestConstants.TOKEN);
        mHandler.setRequestedSignInCredentialForEmail(facebookAuthCredential, TestConstants.EMAIL);


        // mock sign in with Google credential to always work
        when(mMockAuth.signInWithCredential(any(GoogleAuthCredential.class)))
                .thenReturn(AutoCompleteTask.forSuccess(FakeAuthResult.INSTANCE));

        // Mock linking with Facebook to always work
        when(FakeAuthResult.INSTANCE.getUser().linkWithCredential(facebookAuthCredential))
                .thenReturn(new AutoContinueTask<>(FakeAuthResult.INSTANCE,
                        FakeAuthResult.INSTANCE,
                        true,
                        null));

        mHandler.startSignIn(response);
        shadowOf(Looper.getMainLooper()).idle();

        verify(mMockAuth).signInWithCredential(any(GoogleAuthCredential.class));
        verify(FakeAuthResult.INSTANCE.getUser()).linkWithCredential(facebookAuthCredential);

        InOrder inOrder = inOrder(mResponseObserver);
        inOrder.verify(mResponseObserver)
                .onChanged(argThat(ResourceMatchers.isLoading()));
        inOrder.verify(mResponseObserver)
                .onChanged(argThat(ResourceMatchers.isSuccess()));
    }

    @Test
    public void testSignIn_anonymousUpgradeEnabledWithDifferentIdp_expectMergeFailure() {
        mHandler.getOperation().observeForever(mResponseObserver);
        setupAnonymousUpgrade();

        // We're going to fake a sign in with facebook, where the email belongs
        // to an existing account with a Google provider.
        // We need to link Facebook to this account, and then a merge failure should occur
        // so that the developer can handle it.
        // Before we can link, they need to sign in with Google to prove they own the account.

        // Fake social response from Google
        IdpResponse response = new IdpResponse.Builder(new User.Builder(
                GoogleAuthProvider.PROVIDER_ID, TestConstants.EMAIL).build())
                .setToken(TestConstants.TOKEN)
                .build();

        // Set facebook credential
        AuthCredential facebookAuthCredential =
                FacebookAuthProvider.getCredential(TestConstants.TOKEN);
        mHandler.setRequestedSignInCredentialForEmail(facebookAuthCredential, TestConstants.EMAIL);

        when(mScratchMockAuth.signInWithCredential(any(GoogleAuthCredential.class)))
                .thenReturn(AutoCompleteTask.forSuccess(FakeAuthResult.INSTANCE));

        // Mock linking with Facebook to always work
        when(FakeAuthResult.INSTANCE.getUser().linkWithCredential(facebookAuthCredential))
                .thenReturn(new AutoContinueTask<>(FakeAuthResult.INSTANCE,
                        FakeAuthResult.INSTANCE,
                        true,
                        null));

        mHandler.startSignIn(response);

        verify(mScratchMockAuth).signInWithCredential(any(GoogleAuthCredential.class));
        verify(FakeAuthResult.INSTANCE.getUser()).linkWithCredential(facebookAuthCredential);

        InOrder inOrder = inOrder(mResponseObserver);
        inOrder.verify(mResponseObserver)
                .onChanged(argThat(ResourceMatchers.isLoading()));

        ArgumentCaptor<Resource<IdpResponse>> resolveCaptor =
                ArgumentCaptor.forClass(Resource.class);
        inOrder.verify(mResponseObserver).onChanged(resolveCaptor.capture());

        // Merge failure should occur after successful linking
        FirebaseAuthAnonymousUpgradeException e =
                (FirebaseAuthAnonymousUpgradeException) resolveCaptor.getValue().getException();

        AuthCredential credential = ProviderUtils.getAuthCredential(response);
        GoogleAuthCredential responseCredential =
                (GoogleAuthCredential) e.getResponse().getCredentialForLinking();

        assertThat(responseCredential.getProvider()).isEqualTo(credential.getProvider());
        assertThat(responseCredential.getSignInMethod()).isEqualTo(credential.getSignInMethod());
    }

    @Test
    public void testSignIn_genericIdpLinkingFlow_expectImmediateLink() {
        mHandler.getOperation().observeForever(mResponseObserver);

        // Set Facebook credential
        AuthCredential facebookAuthCredential =
                FacebookAuthProvider.getCredential(TestConstants.TOKEN);
        mHandler.setRequestedSignInCredentialForEmail(facebookAuthCredential, TestConstants.EMAIL);

        // Fake social response from Microsoft
        IdpResponse idpResponse = new IdpResponse.Builder(
                new User.Builder(MICROSOFT_PROVIDER, TestConstants.EMAIL)
                        .setName(DISPLAY_NAME)
                        .build())
                .build();

        when(mMockAuth.getCurrentUser()).thenReturn(mMockUser);
        when(mMockUser.linkWithCredential(any(AuthCredential.class)))
                .thenReturn(AutoCompleteTask.forSuccess(FakeAuthResult.INSTANCE));

        mHandler.startSignIn(idpResponse);

        InOrder inOrder = inOrder(mResponseObserver);
        inOrder.verify(mResponseObserver)
                .onChanged(argThat(ResourceMatchers.isLoading()));

        ArgumentCaptor<Resource<IdpResponse>> resolveCaptor =
                ArgumentCaptor.forClass(Resource.class);
        inOrder.verify(mResponseObserver).onChanged(resolveCaptor.capture());

        assertThat(resolveCaptor.getValue().getValue()).isNotNull();
    }

    private void setupAnonymousUpgrade() {
        FlowParameters testParams = TestHelper.getFlowParameters(Collections.singletonList(
                GoogleAuthProvider.PROVIDER_ID), true);
        mHandler.initializeForTesting(testParams, mMockAuth, null);
        when(mMockAuth.getCurrentUser()).thenReturn(mMockUser);
        when(mMockUser.isAnonymous()).thenReturn(true);
        AuthOperationManager.getInstance().mScratchAuth = mScratchMockAuth;
    }
}
