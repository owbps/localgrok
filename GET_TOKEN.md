# Get Your GitHub Personal Access Token

Since GitHub no longer accepts passwords, you need a Personal Access Token.

## Steps to Get Your Token:

1. **Go to GitHub Token Settings:**
   - Visit: https://github.com/settings/tokens
   - Or: GitHub → Your Profile Picture (top right) → Settings → Developer settings → Personal access tokens → Tokens (classic)

2. **Click "Generate new token" → "Generate new token (classic)"**

3. **Fill in the form:**
   - **Note**: "localgrok push access" (or anything you want)
   - **Expiration**: Choose how long (30 days, 90 days, or no expiration)
   - **Select scopes**: Check **`repo`** (this gives full repository access)

4. **Click "Generate token" at the bottom**

5. **COPY THE TOKEN IMMEDIATELY** - You won't be able to see it again!
   - It will look like: `ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`

6. **Use it as your password when pushing:**
   ```bash
   git push -u origin main
   ```
   - Username: `owbps`
   - Password: **paste your token here** (the `ghp_...` string)

## Alternative: Use SSH Instead

If you prefer, you can use SSH keys instead (no password needed):

```bash
# Remove HTTPS remote
git remote remove origin

# Add SSH remote
git remote add origin git@github.com:owbps/localgrok.git

# Push (will use SSH keys if set up)
git push -u origin main
```

But the Personal Access Token method is easier if you haven't set up SSH keys yet.
