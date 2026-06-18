param(
    [string]$Server = "141.37.74.145",
    [string]$User = "chess",
    [string]$ImageName = "searchess/chess-streaming:local",
    [string]$TarFile = "chess-streaming.tar",
    [string]$ContainerName = "chess-streaming",
    [int]$Port = 8082
)

$ErrorActionPreference = "Stop"

Write-Host "==> 1. Building Docker image locally..."
docker build -t $ImageName -f deployment/docker/Dockerfile.streaming .

Write-Host "==> 2. Exporting Docker image to $TarFile..."
if (Test-Path $TarFile) {
    Remove-Item $TarFile
}
docker save -o $TarFile $ImageName

Write-Host "==> 3. Copying image archive to remote server $User@$Server..."
scp $TarFile "$($User)@$($Server):~/"

Write-Host "==> 4. Deploying image on remote server..."
$sshCmds = @"
echo 'Stopping old container if exists...'
docker stop $ContainerName 2>/dev/null || true
docker rm $ContainerName 2>/dev/null || true

echo 'Loading new Docker image...'
docker load < ~/$TarFile

echo 'Starting new container...'
docker run -d \
  --name $ContainerName \
  --restart unless-stopped \
  -p $($Port):8082 \
  -e STREAMING_HOST=0.0.0.0 \
  -e STREAMING_PORT=8082 \
  $ImageName

echo 'Cleaning up image archive...'
rm -f ~/$TarFile

echo 'Deployment complete! Listing running containers:'
docker ps --filter name=$ContainerName
"@

ssh "$($User)@$($Server)" $sshCmds

Write-Host ""
Write-Host "==> Success! The chess-streaming service has been deployed to the remote VM."
Write-Host "To access the service, open an SSH tunnel in a new terminal:"
Write-Host "    ssh -L $($Port):localhost:$($Port) $($User)@$($Server)"
Write-Host "Then open your browser and navigate to: http://localhost:$($Port)"
